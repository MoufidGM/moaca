package com.cslsm.web.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest
{
	private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
	private static final String RFC_KEY_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

	private final TotpService totp = new TotpService();

	@Test
	void matchesRfc6238TestVectors()
	{
		// RFC 6238 Appendix B, SHA1 — last 6 digits of the 8-digit reference values
		assertThat(totp.generate(RFC_KEY, 59L / 30)).isEqualTo("287082");
		assertThat(totp.generate(RFC_KEY, 1111111109L / 30)).isEqualTo("081804");
		assertThat(totp.generate(RFC_KEY, 1111111111L / 30)).isEqualTo("050471");
		assertThat(totp.generate(RFC_KEY, 1234567890L / 30)).isEqualTo("005924");
		assertThat(totp.generate(RFC_KEY, 2000000000L / 30)).isEqualTo("279037");
		assertThat(totp.generate(RFC_KEY, 20000000000L / 30)).isEqualTo("353130");
	}

	@Test
	void base32RoundTrip()
	{
		assertThat(Base32.encode(RFC_KEY)).isEqualTo(RFC_KEY_BASE32);
		assertThat(Base32.decode(RFC_KEY_BASE32)).isEqualTo(RFC_KEY);
		assertThat(Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(RFC_KEY);
	}

	@Test
	void verifyAcceptsCurrentAndAdjacentStepOnly()
	{
		assertThat(totp.verify(RFC_KEY_BASE32, "287082", Instant.ofEpochSecond(59))).isEqualTo(1);
		assertThat(totp.verify(RFC_KEY_BASE32, "287 082", Instant.ofEpochSecond(89))).isEqualTo(1);
		assertThat(totp.verify(RFC_KEY_BASE32, "287082", Instant.ofEpochSecond(149))).isEqualTo(-1);
	}

	@Test
	void verifyRejectsMalformedCodes()
	{
		assertThat(totp.verify(RFC_KEY_BASE32, "000000", Instant.ofEpochSecond(59))).isEqualTo(-1);
		assertThat(totp.verify(RFC_KEY_BASE32, "28708a", Instant.ofEpochSecond(59))).isEqualTo(-1);
		assertThat(totp.verify(RFC_KEY_BASE32, "2870821", Instant.ofEpochSecond(59))).isEqualTo(-1);
		assertThat(totp.verify(RFC_KEY_BASE32, null, Instant.ofEpochSecond(59))).isEqualTo(-1);
	}

	@Test
	void newSecretsAre160BitsAndUnique()
	{
		String a = totp.newSecret();
		String b = totp.newSecret();
		assertThat(a).hasSize(32);
		assertThat(Base32.decode(a)).hasSize(20);
		assertThat(a).isNotEqualTo(b);
	}
}
