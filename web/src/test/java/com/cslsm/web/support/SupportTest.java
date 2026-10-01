package com.cslsm.web.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SupportTest
{
	@Test
	void moneyAcceptsHowPeopleTypeAmounts()
	{
		assertThat(Money.parse("1250")).isEqualTo(1250.0);
		assertThat(Money.parse("1 250,50")).isEqualTo(1250.5);
		assertThat(Money.parse("1.250,50")).isEqualTo(1250.5);
		assertThat(Money.parse("1,250.50")).isEqualTo(1250.5);
		assertThat(Money.parse("1,250")).isEqualTo(1250.0);
		assertThat(Money.parse("1,250,000")).isEqualTo(1250000.0);
		assertThat(Money.parse("250,5")).isEqualTo(250.5);
		assertThat(Money.parse("80000 DH")).isEqualTo(80000.0);
		assertThat(Money.parse("abc")).isNull();
		assertThat(Money.parse("1.2.3")).isNull();
		assertThat(Money.isValidAmount(0.0)).isFalse();
		assertThat(Money.isValidAmount(-5.0)).isFalse();
		assertThat(Money.isValidAmount(20_000_000.0)).isFalse();
	}

	@Test
	void receiptsAreRecognisedByContentNotName()
	{
		assertThat(FileTypes.sniffReceipt("%PDF-1.7 xxxxxxxx".getBytes()).extension).isEqualTo("pdf");
		assertThat(FileTypes.sniffReceipt(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0}).extension).isEqualTo("png");
		assertThat(FileTypes.sniffReceipt(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0}).extension).isEqualTo("jpg");
		assertThat(FileTypes.sniffReceipt("<html><script>alert(1)</script>".getBytes())).isNull();
		assertThat(FileTypes.sniffReceipt("MZ executable padding....".getBytes())).isNull();
	}

	@Test
	void hashesAndNames()
	{
		assertThat(FileTypes.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(FileTypes.safeName("C:\\Users\\desk\\DL-01-09-2026 (1).xlsx")).isEqualTo("DL-01-09-2026 (1).xlsx");
		assertThat(FileTypes.safeName("../../etc/passwd")).isEqualTo("passwd");
		assertThat(FileTypes.safeName("reçu <b>.jpg")).isEqualTo("reçu _b_.jpg");
	}
}
