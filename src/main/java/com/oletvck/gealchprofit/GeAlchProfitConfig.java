package com.oletvck.gealchprofit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(GeAlchProfitConfig.GROUP)
public interface GeAlchProfitConfig extends Config
{
	String GROUP = "gealchprofit";

	@ConfigSection(
		name = "Nature runes",
		description = "How the cost of nature runes is factored into profit",
		position = 0
	)
	String natureSection = "natureSection";

	@ConfigItem(
		keyName = "includeNatureCost",
		name = "Subtract nature rune cost",
		description = "Include the cost of one nature rune per cast in the profit calculation.",
		section = natureSection,
		position = 0
	)
	default boolean includeNatureCost()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useLiveNaturePrice",
		name = "Use live nature price",
		description = "Use the current Grand Exchange price of a nature rune. Turn off to use the fixed price below.",
		section = natureSection,
		position = 1
	)
	default boolean useLiveNaturePrice()
	{
		return true;
	}

	@ConfigItem(
		keyName = "natureRunePrice",
		name = "Fixed nature price",
		description = "Nature rune price (gp) used when 'Use live nature price' is off.",
		section = natureSection,
		position = 2
	)
	default int natureRunePrice()
	{
		return 180;
	}

	@ConfigItem(
		keyName = "alchOnlyMode",
		name = "Alch only mode",
		description = "While this is on, the plugin does not track buys on the GE — only items you high alch are tracked. Keeps your flips out of the panel.",
		position = 1
	)
	default boolean alchOnlyMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "trackOnlyProfitable",
		name = "Hide unknown buy price rows",
		description = "Hide items in the panel that have no recorded or manual buy price yet.",
		position = 2
	)
	default boolean trackOnlyProfitable()
	{
		return false;
	}

	@ConfigSection(
		name = "Stats sync",
		description = "Optionally upload your stats to a server to view your history online",
		position = 10,
		closedByDefault = true
	)
	String statsSection = "statsSection";

	@ConfigItem(
		keyName = "uploadStats",
		name = "Upload stats",
		description = "When on, this plugin sends your alch stats (cast counts, profit, alched item "
			+ "names, and Magic XP/level) to the Stats server URL below so you can view your history "
			+ "on the web dashboard. Your account password is never sent. This is OFF by default.",
		section = statsSection,
		position = 0
	)
	default boolean uploadStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = "statsApiUrl",
		name = "Stats server URL",
		description = "Base URL of the Alch Assistant stats server (e.g. https://stats.example.com).",
		section = statsSection,
		position = 1
	)
	default String statsApiUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "statsApiKey",
		name = "Stats API key",
		description = "The API key from your dashboard account. Paste it here to link uploads to your account.",
		section = statsSection,
		position = 2,
		secret = true
	)
	default String statsApiKey()
	{
		return "";
	}
}
