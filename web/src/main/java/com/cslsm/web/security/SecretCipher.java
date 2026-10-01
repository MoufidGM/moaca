package com.cslsm.web.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts small secrets (two-factor seeds) before they are stored in the database, so a
 * copied database file alone does not let anyone generate valid codes.
 *
 * AES-256-GCM with a random 96-bit IV per value; the key is derived (SHA-256) from
 * CSLSM_SECRET_KEY, which lives only in /etc/cslsm/cslsm.env on the server.
 * Stored format: "v1:" + Base64(iv || ciphertext+tag).
 */
public class SecretCipher
{
	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	static final int MIN_KEY_LENGTH = 32;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public SecretCipher(String secretKey)
	{
		if (secretKey == null || secretKey.trim().length() < MIN_KEY_LENGTH)
		{
			throw new IllegalStateException("CSLSM_SECRET_KEY must be set to at least " + MIN_KEY_LENGTH
					+ " characters. Generate one with: openssl rand -base64 48");
		}
		try
		{
			byte[] derived = MessageDigest.getInstance("SHA-256").digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
			this.key = new SecretKeySpec(derived, "AES");
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	public String encrypt(String plaintext)
	{
		try
		{
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
			return PREFIX + Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	public String decrypt(String stored)
	{
		if (stored == null || !stored.startsWith(PREFIX))
		{
			throw new IllegalArgumentException("Unrecognised encrypted value");
		}
		try
		{
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			if (all.length <= IV_BYTES)
			{
				throw new IllegalArgumentException("Encrypted value too short");
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
			byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException e)
		{
			// Wrong key or tampered value (GCM authentication failed).
			throw new IllegalStateException("Could not decrypt value: wrong CSLSM_SECRET_KEY or tampered data", e);
		}
	}
}
