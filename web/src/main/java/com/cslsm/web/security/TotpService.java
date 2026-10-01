package com.cslsm.web.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords (RFC 6238): 6 digits, 30-second steps, HMAC-SHA1 —
 * the defaults every authenticator app supports (Google Authenticator, Microsoft
 * Authenticator, 1Password, Authy…).
 */
public class TotpService
{
	private static final int PERIOD_SECONDS = 30;
	private static final int DIGITS = 6;
	private static final int MODULO = 1_000_000;
	/** Accept one step before/after the current one to tolerate small clock drift. */
	private static final int DRIFT_STEPS = 1;

	private final SecureRandom random = new SecureRandom();

	/** A new random 160-bit secret, Base32-encoded (32 characters). */
	public String newSecret()
	{
		byte[] bytes = new byte[20];
		random.nextBytes(bytes);
		return Base32.encode(bytes);
	}

	/** The URI encoded in the enrollment QR code. */
	public String otpauthUri(String issuer, String account, String secret)
	{
		return "otpauth://totp/" + encode(issuer + ":" + account)
				+ "?secret=" + secret
				+ "&issuer=" + encode(issuer)
				+ "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
	}

	/**
	 * Checks a code against the secret.
	 *
	 * @return the 30-second step the code matched (store it to block replay), or -1
	 */
	public long verify(String base32Secret, String code, Instant now)
	{
		if (code == null)
		{
			return -1;
		}
		String digits = code.replace(" ", "").trim();
		if (!digits.matches("\\d{" + DIGITS + "}"))
		{
			return -1;
		}
		byte[] key = Base32.decode(base32Secret);
		byte[] given = digits.getBytes(StandardCharsets.US_ASCII);
		long current = now.getEpochSecond() / PERIOD_SECONDS;

		long matched = -1;
		// Check every candidate (no early exit) so timing does not reveal which one matched.
		for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++)
		{
			byte[] expected = generate(key, step).getBytes(StandardCharsets.US_ASCII);
			if (MessageDigest.isEqual(expected, given) && matched < 0)
			{
				matched = step;
			}
		}
		return matched;
	}

	/** RFC 6238 / RFC 4226 code for one time step. Package-private for tests. */
	String generate(byte[] key, long step)
	{
		try
		{
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24)
					| ((hash[offset + 1] & 0xff) << 16)
					| ((hash[offset + 2] & 0xff) << 8)
					| (hash[offset + 3] & 0xff);
			String code = Integer.toString(binary % MODULO);
			StringBuilder padded = new StringBuilder(DIGITS);
			for (int i = code.length(); i < DIGITS; i++)
			{
				padded.append('0');
			}
			return padded.append(code).toString();
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("HmacSHA1 unavailable", e);
		}
	}

	private static String encode(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
