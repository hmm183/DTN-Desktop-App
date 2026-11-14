/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import core.ConnectionListener;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimClock;

/**
 * Dashboard Metrics panel showing live simulation statistics.
 * Tracks message counts, delivery ratio, latency, active links, and hop counts.
 */
@SuppressWarnings("serial")
public class MetricsPanel extends JPanel
		implements ConnectionListener, MessageListener {

	private static final Color BG = new Color(248, 250, 252);
	private static final Color CARD_BG = Color.WHITE;
	private static final Color CARD_BORDER = new Color(226, 232, 240);
	private static final Color ACCENT_BLUE = new Color(2, 132, 199);
	private static final Color ACCENT_GREEN = new Color(16, 185, 129);
	private static final Color ACCENT_RED = new Color(239, 68, 68);
	private static final Color ACCENT_PURPLE = new Color(147, 51, 234);
	private static final Color ACCENT_AMBER = new Color(245, 158, 11);
	private static final Color ACCENT_TEAL = new Color(13, 148, 136);
	private static final Color TEXT_MAIN = new Color(15, 23, 42);
	private static final Color TEXT_SUB = new Color(100, 116, 139);

	private static final Font FONT_TITLE = new Font("SansSerif", Font.BOLD, 11);
	private static final Font FONT_VALUE = new Font("SansSerif", Font.BOLD, 22);
	private static final Font FONT_UNIT = new Font("SansSerif", Font.PLAIN, 10);
	private static final Font FONT_SECTION = new Font("SansSerif", Font.BOLD, 12);

	// Counters
	private int totalCreated = 0;
	private int totalDelivered = 0;
	private int totalRelayed = 0;
	private int totalDropped = 0;
	private int totalAborted = 0;
	private int totalRemoved = 0;
	private int activeConnections = 0;
	private int totalConnections = 0;
	private double totalLatency = 0;
	private int totalHops = 0;
	private double minLatency = Double.MAX_VALUE;
	private double maxLatency = 0;

	// Card labels
	private JLabel lblCreated;
	private JLabel lblDelivered;
	private JLabel lblRelayed;
	private JLabel lblDropped;
	private JLabel lblAborted;
	private JLabel lblDeliveryRatio;
	private JLabel lblAvgLatency;
	private JLabel lblMinLatency;
	private JLabel lblMaxLatency;
	private JLabel lblAvgHops;
	private JLabel lblActiveLinks;
	private JLabel lblTotalConnections;
	private JLabel lblSimTime;
	private JLabel lblOverhead;

	public MetricsPanel() {
		setLayout(new BorderLayout());
		setBackground(BG);
		setBorder(new EmptyBorder(8, 8, 8, 8));
		initUI();

		// Auto-refresh timer
		Timer refreshTimer = new Timer(500, e -> refreshDisplay());
		refreshTimer.start();
	}

	private void initUI() {
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(BG);

		// Section: Simulation Status
		content.add(createSectionLabel("SIMULATION STATUS"));
		JPanel statusGrid = new JPanel(new GridLayout(1, 2, 6, 6));
		statusGrid.setBackground(BG);
		statusGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
		lblSimTime = addCard(statusGrid, "Sim Time", "0.0s", ACCENT_BLUE);
		lblActiveLinks = addCard(statusGrid, "Active Links", "0", ACCENT_TEAL);
		content.add(statusGrid);
		content.add(createSpacer(6));

		// Section: Message Statistics
		content.add(createSectionLabel("MESSAGE STATISTICS"));
		JPanel msgGrid = new JPanel(new GridLayout(2, 3, 6, 6));
		msgGrid.setBackground(BG);
		msgGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
		lblCreated = addCard(msgGrid, "Created", "0", ACCENT_BLUE);
		lblDelivered = addCard(msgGrid, "Delivered", "0", ACCENT_GREEN);
		lblRelayed = addCard(msgGrid, "Relayed", "0", ACCENT_TEAL);
		lblDropped = addCard(msgGrid, "Dropped", "0", ACCENT_RED);
		lblAborted = addCard(msgGrid, "Aborted", "0", ACCENT_AMBER);
		lblDeliveryRatio = addCard(msgGrid, "Delivery Ratio", "0%", ACCENT_PURPLE);
		content.add(msgGrid);
		content.add(createSpacer(6));

		// Section: Performance
		content.add(createSectionLabel("PERFORMANCE"));
		JPanel perfGrid = new JPanel(new GridLayout(2, 2, 6, 6));
		perfGrid.setBackground(BG);
		perfGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
		lblAvgLatency = addCard(perfGrid, "Avg Latency", "--", ACCENT_BLUE);
		lblMinLatency = addCard(perfGrid, "Min Latency", "--", ACCENT_GREEN);
		lblMaxLatency = addCard(perfGrid, "Max Latency", "--", ACCENT_RED);
		lblAvgHops = addCard(perfGrid, "Avg Hops", "--", ACCENT_AMBER);
		content.add(perfGrid);
		content.add(createSpacer(6));

		// Section: Network
		content.add(createSectionLabel("NETWORK"));
		JPanel netGrid = new JPanel(new GridLayout(1, 2, 6, 6));
		netGrid.setBackground(BG);
		netGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
		lblTotalConnections = addCard(netGrid, "Total Connections", "0", ACCENT_BLUE);
		lblOverhead = addCard(netGrid, "Overhead Ratio", "--", ACCENT_PURPLE);
		content.add(netGrid);

		add(content, BorderLayout.NORTH);
	}

	private JLabel createSectionLabel(String text) {
		JLabel lbl = new JLabel(text);
		lbl.setFont(FONT_SECTION);
		lbl.setForeground(ACCENT_BLUE);
		lbl.setBorder(new EmptyBorder(6, 2, 4, 0));
		lbl.setAlignmentX(LEFT_ALIGNMENT);
		return lbl;
	}

	private JPanel createSpacer(int height) {
		JPanel spacer = new JPanel();
		spacer.setBackground(BG);
		spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		spacer.setPreferredSize(new Dimension(0, height));
		return spacer;
	}

	private JLabel addCard(JPanel parent, String title, String initValue, Color accent) {
		JPanel card = new JPanel(new BorderLayout(0, 0));
		card.setBackground(CARD_BG);
		card.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(CARD_BORDER, 1),
				new EmptyBorder(6, 8, 6, 8)));

		JLabel titleLbl = new JLabel(title);
		titleLbl.setFont(FONT_UNIT);
		titleLbl.setForeground(TEXT_SUB);
		card.add(titleLbl, BorderLayout.NORTH);

		JLabel valueLbl = new JLabel(initValue);
		valueLbl.setFont(FONT_VALUE);
		valueLbl.setForeground(accent);
		valueLbl.setHorizontalAlignment(SwingConstants.CENTER);
		card.add(valueLbl, BorderLayout.CENTER);

		parent.add(card);
		return valueLbl;
	}

	private void refreshDisplay() {
		double simTime = SimClock.getTime();
		if (simTime >= 3600) {
			int h = (int)(simTime / 3600);
			int m = (int)((simTime % 3600) / 60);
			lblSimTime.setText(String.format("%dh %dm", h, m));
		} else if (simTime >= 60) {
			int m = (int)(simTime / 60);
			double s = simTime % 60;
			lblSimTime.setText(String.format("%dm %.0fs", m, s));
		} else {
			lblSimTime.setText(String.format("%.1fs", simTime));
		}

		lblCreated.setText(String.valueOf(totalCreated));
		lblDelivered.setText(String.valueOf(totalDelivered));
		lblRelayed.setText(String.valueOf(totalRelayed));
		lblDropped.setText(String.valueOf(totalDropped));
		lblAborted.setText(String.valueOf(totalAborted));
		lblActiveLinks.setText(String.valueOf(activeConnections));
		lblTotalConnections.setText(String.valueOf(totalConnections));

		if (totalCreated > 0) {
			double ratio = (double) totalDelivered / totalCreated * 100;
			lblDeliveryRatio.setText(String.format("%.1f%%", ratio));
		}

		if (totalDelivered > 0) {
			lblAvgLatency.setText(String.format("%.1fs", totalLatency / totalDelivered));
			lblAvgHops.setText(String.format("%.1f", (double) totalHops / totalDelivered));
			lblMinLatency.setText(String.format("%.1fs", minLatency));
			lblMaxLatency.setText(String.format("%.1fs", maxLatency));
		}

		if (totalDelivered > 0 && totalRelayed > 0) {
			double overhead = (double)(totalRelayed - totalDelivered) / totalDelivered;
			lblOverhead.setText(String.format("%.2f", overhead));
		}
	}

	// ConnectionListener
	public void hostsConnected(DTNHost host1, DTNHost host2) {
		activeConnections++;
		totalConnections++;
	}

	public void hostsDisconnected(DTNHost host1, DTNHost host2) {
		activeConnections = Math.max(0, activeConnections - 1);
	}

	// MessageListener
	public void newMessage(Message m) {
		totalCreated++;
	}

	public void messageTransferred(Message m, DTNHost from, DTNHost to,
			boolean firstDelivery) {
		if (firstDelivery) {
			totalDelivered++;
			double latency = SimClock.getTime() - m.getCreationTime();
			totalLatency += latency;
			totalHops += m.getHopCount();
			if (latency < minLatency) minLatency = latency;
			if (latency > maxLatency) maxLatency = latency;
		} else if (to != m.getTo()) {
			totalRelayed++;
		}
	}

	public void messageDeleted(Message m, DTNHost where, boolean dropped) {
		if (dropped) {
			totalDropped++;
		} else {
			totalRemoved++;
		}
	}

	public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
		totalAborted++;
	}

	public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
		// tracked for relay count in messageTransferred
	}
}
