package com.cslsm.web.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Identifies uploaded files by their content (magic bytes), never by the name or the type the
 * browser claims, and computes content hashes.
 */
public final class FileTypes
{
	/** An accepted receipt type: stored extension and the Content-Type used when serving it. */
	public static final class Kind
	{
		public final String extension;
		public final String contentType;

		Kind(String extension, String contentType)
		{
			this.extension = extension;
			this.contentType = contentType;
		}
	}

	public static final Kind PDF = new Kind("pdf", "application/pdf");
	public static final Kind JPEG = new Kind("jpg", "image/jpeg");
	public static final Kind PNG = new Kind("png", "image/png");
	public static final Kind WEBP = new Kind("webp", "image/webp");
	public static final Kind HEIC = new Kind("heic", "image/heic");

	private FileTypes()
	{
	}

	/** @return the receipt kind, or null when the content is not a PDF or a supported image */
	public static Kind sniffReceipt(byte[] b)
	{
		if (b == null || b.length < 12)
		{
			return null;
		}
		if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F')
		{
			return PDF;
		}
		if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF)
		{
			return JPEG;
		}
		if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
		{
			return PNG;
		}
		if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
		{
			return WEBP;
		}
		if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p')
		{
			String brand = new String(b, 8, 4, StandardCharsets.US_ASCII);
			if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("msf1") || brand.equals("hevc"))
			{
				return HEIC;
			}
		}
		return null;
	}

	/** Excel workbook: .xlsx is a ZIP ("PK\3\4"), .xls an OLE2 compound file. */
	public static String sniffWorkbookExtension(byte[] b)
	{
		if (b == null || b.length < 8)
		{
			return null;
		}
		if (b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4)
		{
			return "xlsx";
		}
		if ((b[0] & 0xff) == 0xD0 && (b[1] & 0xff) == 0xCF && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0xE0)
		{
			return "xls";
		}
		return null;
	}

	public static String sha256Hex(byte[] content)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
			StringBuilder sb = new StringBuilder(64);
			for (byte x : digest)
			{
				sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Keeps only the last path segment and harmless characters of a browser-supplied name. */
	public static String safeName(String original)
	{
		if (original == null)
		{
			return "file";
		}
		String name = original.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1);
		name = name.replaceAll("[^A-Za-z0-9 ._()\\-àâäéèêëîïôöùûüçÀÂÄÉÈÊËÎÏÔÖÙÛÜÇ]", "_").trim();
		if (name.isEmpty())
		{
			return "file";
		}
		return name.length() > 120 ? name.substring(name.length() - 120) : name;
	}
}
