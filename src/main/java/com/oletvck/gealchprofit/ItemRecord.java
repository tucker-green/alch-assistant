package com.oletvck.gealchprofit;

import lombok.Data;

/**
 * Per-item bookkeeping for buys and high alchemy casts.
 *
 * <p>This class is serialised to JSON (via Gson) and stored in the RuneLite
 * config, so it must remain a simple POJO with no-arg construction.</p>
 */
@Data
public class ItemRecord
{
	/** Item id (canonical, un-noted). */
	private int itemId;

	/** Display name, cached so the panel never has to touch the client thread. */
	private String name = "";

	/** Total quantity bought on the GE since tracking started. */
	private long buyQty;

	/** Total gp spent buying those items on the GE. */
	private long buySpent;

	/** Manual buy-price override per item, or -1 when unset. */
	private int manualUnitPrice = -1;

	/** Number of high alchemy casts recorded for this item. */
	private long alchCount;

	/** Last known high alch value, cached for the panel. */
	private int haPrice;

	/**
	 * The buy price used for profit, in gp per item.
	 *
	 * @return the manual override if set, otherwise the average GE price paid,
	 *         or -1 if no buy price is known yet.
	 */
	public int effectiveUnitBuyPrice()
	{
		if (manualUnitPrice >= 0)
		{
			return manualUnitPrice;
		}
		if (buyQty > 0)
		{
			return (int) Math.round((double) buySpent / buyQty);
		}
		return -1;
	}

	public boolean hasBuyPrice()
	{
		return effectiveUnitBuyPrice() >= 0;
	}
}
