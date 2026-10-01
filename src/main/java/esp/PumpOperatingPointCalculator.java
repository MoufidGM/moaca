package esp;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

public final class PumpOperatingPointCalculator {

	// ------------ Data classes ------------

	/** Fluid properties at pump depth. */
	public static final class FluidProperties {
		public final double oilApi;
		public final double waterCutFraction;
		public final double waterSpecificGravity;
		public final double mixtureSpecificGravity;
		public final double densityKgPerM3;

		private FluidProperties(double oilApi,
								double waterCutFraction,
								double waterSpecificGravity,
								double mixtureSpecificGravity,
								double densityKgPerM3) {
			this.oilApi = oilApi;
			this.waterCutFraction = waterCutFraction;
			this.waterSpecificGravity = waterSpecificGravity;
			this.mixtureSpecificGravity = mixtureSpecificGravity;
			this.densityKgPerM3 = densityKgPerM3;
		}

		@Override
		public String toString() {
			return String.format(
					"Fluid{API=%.2f, WC=%.2f, SGw=%.3f, SGmix=%.3f, rho=%.1f kg/m3}",
					oilApi, waterCutFraction, waterSpecificGravity,
					mixtureSpecificGravity, densityKgPerM3
			);
		}
	}

	/** Pump curve built from per-stage head polynomial + stage count. */
	public static final class PumpCurve {
		private final double[] headCoeffsPerStageFt;
		private final int stages;
		private final double refFreqHz;

		/**
		 * @param headCoeffsPerStageFt H1..Hn per-stage head coeffs (ft) vs Q (bpd) at refFreqHz
		 * @param stages                number of stages in the pump
		 * @param refFreqHz             reference frequency of the curve (Hz), e.g. 50
		 */
		public PumpCurve(double[] headCoeffsPerStageFt, int stages, double refFreqHz) {
			this.headCoeffsPerStageFt = headCoeffsPerStageFt.clone();
			this.stages = stages;
			this.refFreqHz = refFreqHz;
		}

		/** Total pump head (ft) at flow (bpd) and frequency (Hz). */
		public double headFt(double flowBpd, double freqHz) {
			// 1) Scale flow back to reference speed
			double qRef = flowBpd * refFreqHz / freqHz;

			// 2) Per-stage head at Q_ref: H = a0 + a1 Q + a2 Q^2 + ...
			double hStageRef = 0.0;
			double qPow = 1.0;
			for (double a : headCoeffsPerStageFt) {
				hStageRef += a * qPow;
				qPow *= qRef;
			}

			// 3) Total head at ref speed, then speed scaling (H ∝ n^2)
			double hTotalRef = hStageRef * stages;
			double ratio = freqHz / refFreqHz;
			return hTotalRef * ratio * ratio;
		}
	}

	/** Resulting operating point on the pump curve. */
	public static final class OperatingPoint {
		public final double flowBpd;
		public final double headFt;
		public final double frequencyHz;

		public OperatingPoint(double flowBpd, double headFt, double frequencyHz) {
			this.flowBpd = flowBpd;
			this.headFt = headFt;
			this.frequencyHz = frequencyHz;
		}

		@Override
		public String toString() {
			return String.format("OperatingPoint{Q=%.1f bpd, H=%.1f ft, f=%.2f Hz}",
					flowBpd, headFt, frequencyHz);
		}
	}

	// ------------ Public API ------------

	public static FluidProperties computeFluidProperties(double oilApi,
														 double waterCutFraction,
														 double waterSpecificGravity) {
		double sgOil = 141.5 / (oilApi + 131.5);  // API ↔ SG formula
		double fw = waterCutFraction;
		double sgMix = fw * waterSpecificGravity + (1.0 - fw) * sgOil;
		double rho = sgMix * 1000.0; // kg/m3 (rho_water ≈ 1000)
		return new FluidProperties(oilApi, waterCutFraction, waterSpecificGravity, sgMix, rho);
	}

	/** Pump head from downhole intake & discharge pressures (psi). */
	public static double computePumpHeadFt(double intakePsi,
										   double dischargePsi,
										   FluidProperties fluid) {
		double deltaPsi = dischargePsi - intakePsi;
		double deltaPa  = deltaPsi * 6894.76;   // psi → Pa
		double g = 9.80665;
		double headM = deltaPa / (fluid.densityKgPerM3 * g);
		return headM * 3.28084;                // m → ft
	}

	public static OperatingPoint computeOperatingPoint(PumpCurve curve,
													   double freqHz,
													   double targetHeadFt,
													   double minFlowBpd,
													   double maxFlowBpd) {
		double q = solveFlowForHead(curve, freqHz, targetHeadFt, minFlowBpd, maxFlowBpd);
		return new OperatingPoint(q, targetHeadFt, freqHz);
	}

	// ------------ Solver (bisection) ------------

	private static double solveFlowForHead(PumpCurve curve,
										   double freqHz,
										   double targetHeadFt,
										   double qMin,
										   double qMax) {
		double fMin = curve.headFt(qMin, freqHz) - targetHeadFt;
		double fMax = curve.headFt(qMax, freqHz) - targetHeadFt;

		if (fMin * fMax > 0) {
			// Target not bracketed; return closer endpoint.
			return (Math.abs(fMin) < Math.abs(fMax)) ? qMin : qMax;
		}

		double left = qMin;
		double right = qMax;
		for (int i = 0; i < 60; i++) {
			double mid = 0.5 * (left + right);
			double fMid = curve.headFt(mid, freqHz) - targetHeadFt;
			if (Math.abs(fMid) < 1e-3) {
				return mid;
			}
			if (fMin * fMid <= 0) {
				right = mid;
				fMax = fMid;
			} else {
				left = mid;
				fMin = fMid;
			}
		}
		return 0.5 * (left + right);
	}

	// ------------ Simple chart panel ------------

	private static final class PumpChartPanel extends JPanel {
		private final double[] q;
		private final double[] h;
		private final OperatingPoint op;
		private final String title;

		public PumpChartPanel(double[] q, double[] h, OperatingPoint op, String title) {
			this.q = q;
			this.h = h;
			this.op = op;
			this.title = title;
			setBackground(Color.WHITE);
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);

			int w = getWidth();
			int hPix = getHeight();

			int leftPad = 70;
			int rightPad = 20;
			int topPad = 40;
			int bottomPad = 50;

			double maxQ = 0;
			double maxH = 0;
			for (double v : q) {
				if (v > maxQ) maxQ = v;
			}
			for (double v : h) {
				if (v > maxH) maxH = v;
			}
			// Ensure operating point is in range
			if (op.flowBpd > maxQ) maxQ = op.flowBpd;
			if (op.headFt > maxH) maxH = op.headFt;

			double plotW = w - leftPad - rightPad;
			double plotH = hPix - topPad - bottomPad;

			// Axes
			g2.setColor(Color.BLACK);
			// Y-axis
			g2.drawLine(leftPad, topPad, leftPad, hPix - bottomPad);
			// X-axis
			g2.drawLine(leftPad, hPix - bottomPad, w - rightPad, hPix - bottomPad);

			// Title
			g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
			g2.drawString(title, leftPad, topPad - 10);

			// Axis labels
			g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
			g2.drawString("Flow (bpd)", w / 2 - 30, hPix - 15);
			AffineTransform orig = g2.getTransform();
			g2.rotate(-Math.PI / 2);
			g2.drawString("Head (ft)", -hPix / 2 - 20, 20);
			g2.setTransform(orig);

			// Ticks (simple 5 divisions)
			g2.setColor(Color.GRAY);
			int divisions = 5;
			for (int i = 0; i <= divisions; i++) {
				double qVal = maxQ * i / divisions;
				int x = (int) (leftPad + (qVal / maxQ) * plotW);
				g2.drawLine(x, hPix - bottomPad, x, hPix - bottomPad + 5);
				String label = String.format("%.0f", qVal);
				int strW = g2.getFontMetrics().stringWidth(label);
				g2.drawString(label, x - strW / 2, hPix - bottomPad + 20);
			}
			for (int i = 0; i <= divisions; i++) {
				double hVal = maxH * i / divisions;
				int y = (int) (hPix - bottomPad - (hVal / maxH) * plotH);
				g2.drawLine(leftPad - 5, y, leftPad, y);
				String label = String.format("%.0f", hVal);
				int strW = g2.getFontMetrics().stringWidth(label);
				g2.drawString(label, leftPad - 10 - strW, y + 4);
			}

			// Pump curve
			Path2D path = new Path2D.Double();
			for (int i = 0; i < q.length; i++) {
				int x = (int) (leftPad + (q[i] / maxQ) * plotW);
				int y = (int) (hPix - bottomPad - (h[i] / maxH) * plotH);
				if (i == 0) {
					path.moveTo(x, y);
				} else {
					path.lineTo(x, y);
				}
			}
			g2.setColor(new Color(30, 144, 255)); // blue-ish
			g2.setStroke(new BasicStroke(2f));
			g2.draw(path);

			// Operating point
			int opX = (int) (leftPad + (op.flowBpd / maxQ) * plotW);
			int opY = (int) (hPix - bottomPad - (op.headFt / maxH) * plotH);
			double r = 6;
			Shape dot = new Ellipse2D.Double(opX - r, opY - r, 2 * r, 2 * r);
			g2.setColor(Color.RED);
			g2.fill(dot);
			g2.setColor(Color.BLACK);
			String opLabel = String.format("OP: %.0f bpd, %.0f ft", op.flowBpd, op.headFt);
			g2.drawString(opLabel, opX + 8, opY - 8);

			g2.dispose();
		}
	}

	// ------------ MAIN: compute + draw chart ------------

	public static void main(String[] args) {
		// === 1) Fluid from design (example for your SF320 well) ===
		double api = 16.7;
		double waterCut = 0.20;       // 20%
		double waterSG = 1.10;
		FluidProperties fluid = computeFluidProperties(api, waterCut, waterSG);
		System.out.println(fluid);

		// === 2) Head from SCADA PIP / PDP (psi) ===
		double pipPsi = 336.5;
		double pdpPsi = 3681.8;
		double headFt = computePumpHeadFt(pipPsi, pdpPsi, fluid);
		System.out.printf("Measured head: %.1f ft%n", headFt);

		// === 3) Pump curve for Summit III 400 SF320 ===
		double[] HEAD_COEFFS_PER_STAGE_FT = {
				25.086,
				-0.00809595,
				0.00016873,
				-0.00000115,
				0.00000000245,
				-0.00000000000188
		};

		int STAGES = 400;        // <-- put the actual stage count here
		double REF_FREQ_HZ = 50; // coefficients are at 50 Hz

		PumpCurve curve = new PumpCurve(HEAD_COEFFS_PER_STAGE_FT, STAGES, REF_FREQ_HZ);

		// === 4) Compute operating point at current frequency ===
		double freqHz = 51.5;
		OperatingPoint op = computeOperatingPoint(curve, freqHz, headFt,
				0.0, 600.0); // search range in bpd
		System.out.println(op);

		// === 5) Build curve samples for the chart ===
		int nPoints = 200;
		double maxQ = 600.0; // you can set to 550 = Max Plotted Prod
		double[] q = new double[nPoints];
		double[] h = new double[nPoints];
		for (int i = 0; i < nPoints; i++) {
			double qi = maxQ * i / (nPoints - 1);
			q[i] = qi;
			h[i] = curve.headFt(qi, freqHz);
		}

		String title = String.format("Summit III SF320 @ %.1f Hz", freqHz);

		// === 6) Show Swing window ===
		SwingUtilities.invokeLater(() -> {
			JFrame frame = new JFrame("Pump Curve & Operating Point");
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setSize(800, 600);
			frame.setLocationRelativeTo(null);
			frame.setContentPane(new PumpChartPanel(q, h, op, title));
			frame.setVisible(true);
		});
	}
}
