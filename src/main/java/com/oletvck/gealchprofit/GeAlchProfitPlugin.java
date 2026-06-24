package com.oletvck.gealchprofit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Alch Assistant",
	description = "Tracks the profit of items bought on the GE and high alched, including nature rune cost.",
	tags = {"alch", "alchemy", "high", "grand", "exchange", "ge", "profit", "money", "nature"}
)
public class GeAlchProfitPlugin extends Plugin
{
	/** Nature rune item id. */
	static final int NATURE_RUNE = 561;

	/** Magic XP granted by a single High Level Alchemy cast. */
	static final int HIGH_ALCH_XP = 65;

	private static final String CONFIG_KEY_RECORDS = "records";
	private static final String ALCH_TARGET = "High Level Alchemy";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private GeAlchProfitConfig config;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

	private ScheduledFuture<?> uploadTask;

	/** Human-readable status of the last stats upload, shown in the panel. */
	private volatile String lastSyncStatus = "";

	/** itemId -> record. Mutated on the client thread, read (snapshotted) on the EDT. */
	private final Map<Integer, ItemRecord> records = new ConcurrentHashMap<>();

	/** GE slot -> last seen snapshot, used to count only the newly-bought delta. */
	private final Map<Integer, SlotSnapshot> slotSnapshots = new HashMap<>();

	/** Item the player most recently aimed High Level Alchemy at, awaiting the cast to complete. */
	private int pendingAlchItemId = -1;
	private int pendingAlchTick = -1;

	/** Last observed total Magic XP, used to detect per-cast XP gains. -1 = unknown. */
	private int lastMagicXp = -1;

	/** Current Magic XP / level, snapshotted on the client thread for the panel. */
	private volatile int currentMagicXp;
	private volatile int currentMagicLevel;

	/** Total XP required to reach each level (index = level, 1..99). */
	private static final int[] XP_TABLE = buildXpTable();

	private static int[] buildXpTable()
	{
		final int[] table = new int[100];
		double points = 0;
		table[1] = 0;
		for (int lvl = 1; lvl < 99; lvl++)
		{
			points += Math.floor(lvl + 300 * Math.pow(2, lvl / 7.0));
			table[lvl + 1] = (int) Math.floor(points / 4);
		}
		return table;
	}

	/** Current nature rune cost, snapshotted on the client thread for the EDT to read. */
	private volatile int natureCostSnapshot;

	private GeAlchProfitPanel panel;
	private NavigationButton navButton;

	@Provides
	GeAlchProfitConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GeAlchProfitConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadRecords();

		panel = new GeAlchProfitPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Alch Assistant")
			.icon(createIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(this::refreshNatureCost);
		refreshPanel();

		// Periodically upload a stats snapshot (no-op unless the user enables it).
		uploadTask = executor.scheduleWithFixedDelay(this::uploadStatsIfEnabled, 60, 300, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		saveRecords();
		if (uploadTask != null)
		{
			uploadTask.cancel(false);
			uploadTask = null;
		}
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		slotSnapshots.clear();
		pendingAlchItemId = -1;
	}

	// ------------------------------------------------------------------
	// Grand Exchange buy tracking
	// ------------------------------------------------------------------

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		// In alch-only mode we ignore GE activity entirely, so flips don't get
		// added to the panel.
		if (config.alchOnlyMode())
		{
			return;
		}

		final int slot = event.getSlot();
		final GrandExchangeOffer offer = event.getOffer();
		final GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			slotSnapshots.remove(slot);
			return;
		}

		// Only buy offers contribute to a buy price.
		final boolean isBuy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;

		if (!isBuy)
		{
			slotSnapshots.remove(slot);
			return;
		}

		final int itemId = offer.getItemId();
		final SlotSnapshot prev = slotSnapshots.get(slot);

		// First time we see this slot/offer (e.g. fresh placement, or offers
		// replayed at login): seed the baseline without counting, so we never
		// double-count buys made in a previous session.
		if (prev == null || prev.itemId != itemId)
		{
			slotSnapshots.put(slot, new SlotSnapshot(itemId, offer.getQuantitySold(), offer.getSpent()));
			return;
		}

		final long deltaQty = offer.getQuantitySold() - prev.qtyBought;
		final long deltaSpent = offer.getSpent() - prev.spent;

		if (deltaQty > 0 && deltaSpent >= 0)
		{
			final ItemRecord record = getOrCreate(itemId);
			record.setBuyQty(record.getBuyQty() + deltaQty);
			record.setBuySpent(record.getBuySpent() + deltaSpent);
			saveRecords();
			refreshPanel();
		}

		slotSnapshots.put(slot, new SlotSnapshot(itemId, offer.getQuantitySold(), offer.getSpent()));
	}

	// ------------------------------------------------------------------
	// High alchemy detection
	// ------------------------------------------------------------------

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = event.getMenuOption();
		final String target = event.getMenuTarget();
		if (option == null || !option.equals("Cast")
			|| target == null || !target.contains(ALCH_TARGET))
		{
			return;
		}

		int itemId = event.getItemId();

		// Fallback: spell-on-item entries don't always carry an item id, so read
		// the targeted inventory slot widget directly.
		if (itemId < 0)
		{
			final Widget parent = client.getWidget(event.getParam1());
			final int slot = event.getParam0();
			if (parent != null && slot >= 0)
			{
				final Widget child = parent.getChild(slot);
				if (child != null && child.getItemId() > 0)
				{
					itemId = child.getItemId();
				}
			}
		}

		if (itemId >= 0)
		{
			pendingAlchItemId = itemId;
			pendingAlchTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.MAGIC)
		{
			return;
		}

		final int xp = event.getXp();
		final int prev = lastMagicXp;
		lastMagicXp = xp;

		// Each completed High Level Alchemy cast grants Magic XP, firing exactly
		// one StatChanged per cast. Counting these (instead of the cast
		// animation, which does not re-fire for rapid back-to-back casts) means
		// fast alching is captured fully. We attribute the gain to the item the
		// player just clicked the spell on, within a short window.
		//
		// prev < 0 covers the first XP event after launch: the login-time stat
		// load has no pending alch click so it is ignored, but a genuine first
		// cast (pending click set) is still counted.
		final boolean gainedXp = prev < 0 || xp > prev;
		if (gainedXp
			&& pendingAlchItemId >= 0
			&& client.getTickCount() - pendingAlchTick <= 5)
		{
			recordAlch(pendingAlchItemId);
			pendingAlchItemId = -1;
		}
	}

	private void recordAlch(int itemId)
	{
		final ItemRecord record = getOrCreate(itemId);
		record.setAlchCount(record.getAlchCount() + 1);
		record.setHaPrice(highAlchValue(itemId));
		saveRecords();
		refreshPanel();
	}

	// ------------------------------------------------------------------
	// Pricing helpers (call on client thread)
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		refreshNatureCost();

		final int xp = client.getSkillExperience(Skill.MAGIC);
		final int lvl = client.getRealSkillLevel(Skill.MAGIC);
		if (xp != currentMagicXp || lvl != currentMagicLevel)
		{
			currentMagicXp = xp;
			currentMagicLevel = lvl;
			refreshPanel();
		}
	}

	private void refreshNatureCost()
	{
		int cost = config.useLiveNaturePrice()
			? itemManager.getItemPrice(NATURE_RUNE)
			: config.natureRunePrice();
		if (cost < 0)
		{
			cost = 0;
		}
		if (cost != natureCostSnapshot)
		{
			natureCostSnapshot = cost;
			refreshPanel();
		}
	}

	private int highAlchValue(int itemId)
	{
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp == null ? 0 : comp.getHaPrice();
	}

	private ItemRecord getOrCreate(int itemId)
	{
		return records.computeIfAbsent(itemId, id ->
		{
			final ItemRecord r = new ItemRecord();
			r.setItemId(id);
			final ItemComposition comp = itemManager.getItemComposition(id);
			if (comp != null)
			{
				r.setName(comp.getName());
				r.setHaPrice(comp.getHaPrice());
			}
			return r;
		});
	}

	// ------------------------------------------------------------------
	// Values consumed by the panel
	// ------------------------------------------------------------------

	/**
	 * The nature rune cost to subtract per cast, honouring the "subtract nature
	 * cost" toggle. Returns 0 when the toggle is off.
	 */
	int effectiveNatureCost()
	{
		return config.includeNatureCost() ? natureCostSnapshot : 0;
	}

	int rawNatureCost()
	{
		return natureCostSnapshot;
	}

	boolean includeNatureCost()
	{
		return config.includeNatureCost();
	}

	boolean hideUnknownBuyPrice()
	{
		return config.trackOnlyProfitable();
	}

	boolean alchOnlyMode()
	{
		return config.alchOnlyMode();
	}

	void setAlchOnlyMode(boolean on)
	{
		configManager.setConfiguration(GeAlchProfitConfig.GROUP, "alchOnlyMode", on);
		refreshPanel();
	}

	int magicLevel()
	{
		return currentMagicLevel;
	}

	int magicXp()
	{
		return currentMagicXp;
	}

	/** XP remaining until the next Magic level. 0 if maxed or not logged in. */
	long magicXpToNextLevel()
	{
		final int lvl = currentMagicLevel;
		if (lvl < 1 || lvl >= 99)
		{
			return 0;
		}
		return (long) XP_TABLE[lvl + 1] - currentMagicXp;
	}

	/** Progress through the current Magic level, 0.0 to 1.0. */
	double magicLevelProgress()
	{
		final int lvl = currentMagicLevel;
		if (lvl >= 99)
		{
			return 1.0;
		}
		if (lvl < 1)
		{
			return 0.0;
		}
		final int base = XP_TABLE[lvl];
		final int next = XP_TABLE[lvl + 1];
		if (next <= base)
		{
			return 0.0;
		}
		final double p = (double) (currentMagicXp - base) / (next - base);
		return Math.max(0.0, Math.min(1.0, p));
	}

	/** Profit per cast for an item, or null if the buy price is unknown. */
	Integer profitPerAlch(ItemRecord record)
	{
		final int buy = record.effectiveUnitBuyPrice();
		if (buy < 0)
		{
			return null;
		}
		return record.getHaPrice() - buy - effectiveNatureCost();
	}

	/** Snapshot of all records for the EDT, sorted by total profit descending. */
	List<ItemRecord> snapshotRecords()
	{
		final List<ItemRecord> copy = new ArrayList<>(records.values());
		copy.sort((a, b) ->
		{
			final long ta = totalProfit(a);
			final long tb = totalProfit(b);
			return Long.compare(tb, ta);
		});
		return copy;
	}

	long totalProfit(ItemRecord record)
	{
		final Integer per = profitPerAlch(record);
		if (per == null)
		{
			return 0;
		}
		return (long) per * record.getAlchCount();
	}

	// ------------------------------------------------------------------
	// Mutations from the panel (marshalled onto the client thread)
	// ------------------------------------------------------------------

	void setManualBuyPrice(int itemId, int price)
	{
		clientThread.invokeLater(() ->
		{
			final ItemRecord record = getOrCreate(itemId);
			record.setManualUnitPrice(Math.max(0, price));
			saveRecords();
			refreshPanel();
		});
	}

	void clearManualBuyPrice(int itemId)
	{
		clientThread.invokeLater(() ->
		{
			final ItemRecord record = records.get(itemId);
			if (record != null)
			{
				record.setManualUnitPrice(-1);
				saveRecords();
				refreshPanel();
			}
		});
	}

	void removeItem(int itemId)
	{
		clientThread.invokeLater(() ->
		{
			records.remove(itemId);
			saveRecords();
			refreshPanel();
		});
	}

	void resetAll()
	{
		clientThread.invokeLater(() ->
		{
			records.clear();
			slotSnapshots.clear();
			saveRecords();
			refreshPanel();
		});
	}

	// ------------------------------------------------------------------
	// Persistence
	// ------------------------------------------------------------------

	private void refreshPanel()
	{
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	private void saveRecords()
	{
		final Collection<ItemRecord> values = records.values();
		final String json = gson.toJson(values);
		configManager.setConfiguration(GeAlchProfitConfig.GROUP, CONFIG_KEY_RECORDS, json);
	}

	private void loadRecords()
	{
		records.clear();
		final String json = configManager.getConfiguration(GeAlchProfitConfig.GROUP, CONFIG_KEY_RECORDS);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			final Type type = new TypeToken<List<ItemRecord>>()
			{
			}.getType();
			final List<ItemRecord> loaded = gson.fromJson(json, type);
			if (loaded != null)
			{
				for (ItemRecord r : loaded)
				{
					records.put(r.getItemId(), r);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load GE Alch Profit records", e);
		}
	}

	// ------------------------------------------------------------------
	// Optional stats upload (opt-in, see config "Stats sync" section)
	// ------------------------------------------------------------------

	String lastSyncStatus()
	{
		return lastSyncStatus;
	}

	boolean uploadEnabled()
	{
		return config.uploadStats();
	}

	String statsDashboardUrl()
	{
		return config.statsApiUrl();
	}

	/** Builds the cumulative-stats JSON payload. Safe to call off the client thread. */
	private String buildSnapshotJson()
	{
		long totalCasts = 0;
		long totalProfit = 0;
		final List<Map<String, Object>> items = new ArrayList<>();
		for (ItemRecord r : records.values())
		{
			totalCasts += r.getAlchCount();
			final Integer per = profitPerAlch(r);
			final long itemProfit = per == null ? 0 : (long) per * r.getAlchCount();
			totalProfit += itemProfit;
			if (r.getAlchCount() > 0)
			{
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("itemId", r.getItemId());
				m.put("name", r.getName());
				m.put("casts", r.getAlchCount());
				m.put("profit", itemProfit);
				items.add(m);
			}
		}

		final Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("total_casts", totalCasts);
		payload.put("total_profit", totalProfit);
		payload.put("total_nature_cost", totalCasts * (long) natureCostSnapshot);
		payload.put("magic_xp", currentMagicXp);
		payload.put("magic_level", currentMagicLevel);
		payload.put("items", items);
		return gson.toJson(payload);
	}

	private void uploadStatsIfEnabled()
	{
		if (!config.uploadStats())
		{
			return;
		}

		final String url = config.statsApiUrl().trim();
		final String key = config.statsApiKey().trim();
		if (url.isEmpty() || key.isEmpty())
		{
			lastSyncStatus = "Set server URL + API key";
			refreshPanel();
			return;
		}

		final String json;
		try
		{
			json = buildSnapshotJson();
		}
		catch (Exception e)
		{
			log.warn("Failed to build stats snapshot", e);
			return;
		}

		final String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		final Request request = new Request.Builder()
			.url(base + "/v1/stats")
			.addHeader("Authorization", "Bearer " + key)
			.post(RequestBody.create(JSON_MEDIA, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				lastSyncStatus = "Upload failed";
				refreshPanel();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					lastSyncStatus = r.isSuccessful()
						? "Synced"
						: ("Upload error (HTTP " + r.code() + ")");
				}
				refreshPanel();
			}
		});
	}

	/** A small gold-coin style nav icon drawn at runtime (no resource file needed). */
	private static java.awt.image.BufferedImage createIcon()
	{
		final int size = 24;
		final java.awt.image.BufferedImage img =
			new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		final java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new java.awt.Color(0xF4, 0xC4, 0x30));
		g.fillOval(2, 2, size - 4, size - 4);
		g.setColor(new java.awt.Color(0xB8, 0x86, 0x0B));
		g.drawOval(2, 2, size - 5, size - 5);
		g.setColor(new java.awt.Color(0x5A, 0x3E, 0x00));
		g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 13));
		final java.awt.FontMetrics fm = g.getFontMetrics();
		final String s = "A";
		g.drawString(s, (size - fm.stringWidth(s)) / 2, (size + fm.getAscent() - fm.getDescent()) / 2);
		g.dispose();
		return img;
	}

	/** Per-slot snapshot used to compute the newly bought delta. */
	private static final class SlotSnapshot
	{
		private final int itemId;
		private final long qtyBought;
		private final long spent;

		private SlotSnapshot(int itemId, long qtyBought, long spent)
		{
			this.itemId = itemId;
			this.qtyBought = qtyBought;
			this.spent = spent;
		}
	}
}
