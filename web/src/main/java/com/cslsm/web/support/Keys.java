package com.cslsm.web.support;

import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

public final class Keys
{
	private Keys()
	{
	}

	/**
	 * The id of the row just inserted. Works whether the SQLite driver returns only
	 * last_insert_rowid() or the whole inserted row (newer drivers use RETURNING).
	 */
	public static long generatedId(KeyHolder keys)
	{
		Map<String, Object> row = keys.getKeys();
		if (row == null || row.isEmpty())
		{
			throw new IllegalStateException("The database returned no generated id");
		}
		Object value = row.containsKey("id") ? row.get("id") : row.values().iterator().next();
		return ((Number) value).longValue();
	}
}
