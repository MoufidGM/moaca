package com.cslsm.web.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest
{
	private final SecretCipher cipher = new SecretCipher("test-secret-key-that-is-long-enough-123");

	@Test
	void roundTripWithRandomIv()
	{
		String a = cipher.encrypt("JBSWY3DPEHPK3PXP");
		String b = cipher.encrypt("JBSWY3DPEHPK3PXP");
		assertThat(a).startsWith("v1:").isNotEqualTo(b);
		assertThat(cipher.decrypt(a)).isEqualTo("JBSWY3DPEHPK3PXP");
	}

	@Test
	void wrongKeyIsRejected()
	{
		String stored = cipher.encrypt("secret");
		SecretCipher other = new SecretCipher("a-completely-different-key-of-enough-length");
		assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void tamperingIsDetected()
	{
		char[] chars = cipher.encrypt("secret").toCharArray();
		int i = chars.length - 5;
		chars[i] = chars[i] == 'A' ? 'B' : 'A';
		assertThatThrownBy(() -> cipher.decrypt(new String(chars))).isInstanceOf(RuntimeException.class);
	}

	@Test
	void shortOrMissingKeyRefusesToStart()
	{
		assertThatThrownBy(() -> new SecretCipher("short")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecretCipher(null)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecretCipher("")).isInstanceOf(IllegalStateException.class);
	}
}
