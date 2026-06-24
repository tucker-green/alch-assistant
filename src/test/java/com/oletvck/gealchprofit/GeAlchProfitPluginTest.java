package com.oletvck.gealchprofit;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev launcher: starts a RuneLite client with this plugin loaded as a built-in.
 * Run the Gradle "run" task (or this main method) to test in developer mode.
 */
public class GeAlchProfitPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GeAlchProfitPlugin.class);
		RuneLite.main(args);
	}
}
