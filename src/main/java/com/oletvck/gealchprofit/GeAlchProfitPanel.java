package com.oletvck.gealchprofit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Side panel that lists every tracked item with its buy price, alch value,
 * nature rune cost, profit per cast and total profit.
 */
class GeAlchProfitPanel extends PluginPanel
{
	private static final Color PROFIT = new Color(0x4C, 0xAF, 0x50);
	private static final Color LOSS = new Color(0xE5, 0x39, 0x35);

	private final GeAlchProfitPlugin plugin;
	private final JPanel sectionsPanel = new JPanel();
	private final JPanel itemsPanel = new JPanel();
	private final JCheckBox alchOnlyCheck = new JCheckBox("Alch only mode");

	GeAlchProfitPanel(GeAlchProfitPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("Alch Assistant");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		north.add(title);

		alchOnlyCheck.setToolTipText("While this is on, the plugin does not track buys on the GE — only items you high alch. Keeps your flips out of this panel.");
		alchOnlyCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
		alchOnlyCheck.setForeground(Color.LIGHT_GRAY);
		alchOnlyCheck.setFocusable(false);
		alchOnlyCheck.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		alchOnlyCheck.addActionListener(e -> plugin.setAlchOnlyMode(alchOnlyCheck.isSelected()));
		north.add(alchOnlyCheck);

		sectionsPanel.setLayout(new BoxLayout(sectionsPanel, BoxLayout.Y_AXIS));
		sectionsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		sectionsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(sectionsPanel);

		add(north, BorderLayout.NORTH);

		itemsPanel.setLayout(new BoxLayout(itemsPanel, BoxLayout.Y_AXIS));
		itemsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(itemsPanel, BorderLayout.CENTER);

		final JButton reset = new JButton("Reset all");
		reset.addActionListener(e -> plugin.resetAll());
		final JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.add(reset);
		add(south, BorderLayout.SOUTH);

		rebuild();
	}

	/** Rebuilds the whole panel. Must be called on the EDT. */
	void rebuild()
	{
		sectionsPanel.removeAll();
		itemsPanel.removeAll();

		alchOnlyCheck.setSelected(plugin.alchOnlyMode());

		final List<ItemRecord> recs = plugin.snapshotRecords();

		long grandTotal = 0;
		long totalCasts = 0;
		for (ItemRecord r : recs)
		{
			grandTotal += plugin.totalProfit(r);
			totalCasts += r.getAlchCount();
		}

		// --- Summary box ---
		final JPanel summary = new JPanel(new GridLayout(0, 1, 0, 3));
		summary.setOpaque(false);
		summary.add(summaryRow("Nature rune",
			plugin.includeNatureCost() ? gp(plugin.rawNatureCost()) + " gp" : "ignored",
			Color.LIGHT_GRAY));
		summary.add(summaryRow("Total casts", gp(totalCasts), Color.LIGHT_GRAY));
		summary.add(summaryRow("Total profit", gp(grandTotal) + " gp", colorFor(grandTotal)));
		sectionsPanel.add(section("Summary", summary));

		sectionsPanel.add(Box.createVerticalStrut(8));

		// --- Magic XP box ---
		final int lvl = plugin.magicLevel();
		final JPanel magic = new JPanel(new BorderLayout());
		magic.setOpaque(false);

		final JPanel magicRows = new JPanel(new GridLayout(0, 1, 0, 3));
		magicRows.setOpaque(false);
		magicRows.add(summaryRow("XP gained",
			gp(totalCasts * (long) GeAlchProfitPlugin.HIGH_ALCH_XP), Color.LIGHT_GRAY));
		if (lvl >= 1 && lvl < 99)
		{
			magicRows.add(summaryRow("XP to next", gp(plugin.magicXpToNextLevel()), Color.LIGHT_GRAY));
		}
		magic.add(magicRows, BorderLayout.NORTH);

		if (lvl >= 1)
		{
			final int pct = (int) Math.round(plugin.magicLevelProgress() * 100);
			final JProgressBar bar = new JProgressBar(0, 100);
			bar.setValue(pct);
			bar.setStringPainted(true);
			bar.setForeground(PROFIT);
			bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bar.setPreferredSize(new Dimension(10, 20));
			bar.setString(lvl >= 99
				? "Magic 99 (max)"
				: ("Magic " + lvl + " → " + (lvl + 1) + "   " + pct + "%"));

			final JPanel barWrap = new JPanel(new BorderLayout());
			barWrap.setOpaque(false);
			barWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
			barWrap.add(bar, BorderLayout.CENTER);
			magic.add(barWrap, BorderLayout.SOUTH);
		}
		sectionsPanel.add(section("Magic XP", magic));

		// --- Stats sync box (only when upload is enabled) ---
		if (plugin.uploadEnabled())
		{
			final JPanel sync = new JPanel(new BorderLayout());
			sync.setOpaque(false);
			final String status = plugin.lastSyncStatus();
			sync.add(summaryRow("Status", status.isEmpty() ? "Waiting…" : status, Color.LIGHT_GRAY),
				BorderLayout.NORTH);

			final String url = plugin.statsDashboardUrl();
			if (url != null && !url.trim().isEmpty())
			{
				final JButton open = new JButton("Open dashboard");
				open.addActionListener(e -> LinkBrowser.browse(url.trim()));
				final JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
				wrap.setOpaque(false);
				wrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
				wrap.add(open);
				sync.add(wrap, BorderLayout.SOUTH);
			}

			sectionsPanel.add(Box.createVerticalStrut(8));
			sectionsPanel.add(section("Stats sync", sync));
		}

		boolean any = false;
		for (ItemRecord r : recs)
		{
			if (plugin.hideUnknownBuyPrice() && !r.hasBuyPrice())
			{
				continue;
			}
			// In alch-only mode, hide items that have never been alched (pure flips).
			if (plugin.alchOnlyMode() && r.getAlchCount() == 0)
			{
				continue;
			}
			itemsPanel.add(buildItemCard(r));
			itemsPanel.add(Box.createVerticalStrut(6));
			any = true;
		}

		if (!any)
		{
			final JLabel empty = new JLabel("<html>No items yet.<br>Buy something on the GE and<br>high alch it to start tracking.</html>");
			empty.setForeground(Color.GRAY);
			empty.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
			itemsPanel.add(empty);
		}

		revalidate();
		repaint();
	}

	/** Wraps content in a titled, dark, rounded-look card section. */
	private JPanel section(String title, JPanel content)
	{
		final JPanel box = new JPanel(new BorderLayout());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		box.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel t = new JLabel(title.toUpperCase());
		t.setForeground(Color.WHITE);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 11f));
		t.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		box.add(t, BorderLayout.NORTH);

		content.setOpaque(false);
		box.add(content, BorderLayout.CENTER);

		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
		return box;
	}

	private JPanel summaryRow(String label, String value, Color valueColor)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		final JLabel l = new JLabel(label);
		l.setForeground(Color.GRAY);
		final JLabel v = new JLabel(value);
		v.setForeground(valueColor);
		v.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(l, BorderLayout.WEST);
		row.add(v, BorderLayout.EAST);
		return row;
	}

	private JPanel buildItemCard(ItemRecord r)
	{
		final JPanel card = new JPanel();
		card.setLayout(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

		// Header: name + remove
		final JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		final JLabel name = new JLabel(r.getName().isEmpty() ? ("Item " + r.getItemId()) : r.getName());
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeBoldFont());
		header.add(name, BorderLayout.WEST);

		final JButton remove = new JButton("x");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.setToolTipText("Stop tracking this item");
		remove.addActionListener(e -> plugin.removeItem(r.getItemId()));
		header.add(remove, BorderLayout.EAST);
		card.add(header, BorderLayout.NORTH);

		// Stats grid
		final JPanel stats = new JPanel(new GridLayout(0, 2, 4, 2));
		stats.setOpaque(false);
		stats.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		final int buy = r.effectiveUnitBuyPrice();
		final Integer perAlch = plugin.profitPerAlch(r);

		stats.add(stat("Buy", buy < 0 ? "?" : gp(buy) + (r.getManualUnitPrice() >= 0 ? " (set)" : ""), Color.LIGHT_GRAY));
		stats.add(stat("Alch value", gp(r.getHaPrice()), Color.LIGHT_GRAY));
		stats.add(stat("Nature", plugin.includeNatureCost() ? "-" + gp(plugin.rawNatureCost()) : "0", Color.LIGHT_GRAY));
		stats.add(stat("Casts", gp(r.getAlchCount()), Color.LIGHT_GRAY));
		stats.add(stat("Profit / alch", perAlch == null ? "?" : gp(perAlch), perAlch == null ? Color.GRAY : colorFor(perAlch)));
		stats.add(stat("Total", perAlch == null ? "?" : gp(plugin.totalProfit(r)), perAlch == null ? Color.GRAY : colorFor(plugin.totalProfit(r))));
		card.add(stats, BorderLayout.CENTER);

		// Manual buy-price editor
		final JPanel editor = new JPanel(new BorderLayout(4, 0));
		editor.setOpaque(false);
		final JTextField field = new JTextField(r.getManualUnitPrice() >= 0 ? String.valueOf(r.getManualUnitPrice()) : "");
		field.setToolTipText("Set the price you paid per item (overrides the tracked GE average)");
		final JButton set = new JButton("Set buy");
		set.addActionListener(e ->
		{
			final String t = field.getText().replaceAll("[^0-9]", "");
			if (!t.isEmpty())
			{
				plugin.setManualBuyPrice(r.getItemId(), Integer.parseInt(t));
			}
			else
			{
				plugin.clearManualBuyPrice(r.getItemId());
			}
		});
		editor.add(field, BorderLayout.CENTER);
		editor.add(set, BorderLayout.EAST);
		card.add(editor, BorderLayout.SOUTH);

		return card;
	}

	private JPanel stat(String label, String value, Color valueColor)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		final JLabel l = new JLabel(label);
		l.setForeground(Color.GRAY);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 10f));
		final JLabel v = new JLabel(value);
		v.setForeground(valueColor);
		v.setFont(v.getFont().deriveFont(Font.BOLD, 11f));
		p.add(l, BorderLayout.NORTH);
		p.add(v, BorderLayout.SOUTH);
		return p;
	}

	private static Color colorFor(long value)
	{
		if (value > 0)
		{
			return PROFIT;
		}
		if (value < 0)
		{
			return LOSS;
		}
		return Color.LIGHT_GRAY;
	}

	private static String gp(long value)
	{
		return String.format("%,d", value);
	}
}
