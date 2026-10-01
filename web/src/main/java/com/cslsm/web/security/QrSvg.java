package com.cslsm.web.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/**
 * Renders a QR code as inline SVG — no image files, no external service, nothing that
 * would ever send the two-factor secret outside the server.
 */
public final class QrSvg
{
	private QrSvg()
	{
	}

	public static String render(String text, int pixelsPerModule)
	{
		BitMatrix matrix;
		try
		{
			matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
					Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
		}
		catch (WriterException e)
		{
			throw new IllegalStateException("Could not build QR code", e);
		}

		int w = matrix.getWidth();
		int h = matrix.getHeight();
		StringBuilder path = new StringBuilder(w * h);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if (matrix.get(x, y))
				{
					path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
				}
			}
		}
		return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"qr\" viewBox=\"0 0 " + w + " " + h + "\""
				+ " width=\"" + (w * pixelsPerModule) + "\" height=\"" + (h * pixelsPerModule) + "\""
				+ " shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Two-factor QR code\">"
				+ "<rect width=\"" + w + "\" height=\"" + h + "\" fill=\"#ffffff\"/>"
				+ "<path d=\"" + path + "\" fill=\"#000000\"/></svg>";
	}
}
