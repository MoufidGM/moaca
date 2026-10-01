package com.cslsm.web.security;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 Base32 (no padding) — the encoding authenticator apps use for TOTP secrets.
 */
public final class Base32
{
	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	private Base32()
	{
	}

	public static String encode(byte[] data)
	{
		StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
		int buffer = 0;
		int bits = 0;
		for (byte b : data)
		{
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5)
			{
				out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0)
		{
			out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}

	public static byte[] decode(String text)
	{
		String clean = text.replace("=", "").replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
		ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (int i = 0; i < clean.length(); i++)
		{
			int value = ALPHABET.indexOf(clean.charAt(i));
			if (value < 0)
			{
				throw new IllegalArgumentException("Invalid Base32 character");
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8)
			{
				out.write((buffer >> (bits - 8)) & 0xff);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}
}
