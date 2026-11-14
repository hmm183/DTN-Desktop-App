/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimClock;

/**
 * Modern real-time Path Hops Panel matching ONE Simulator's clean light theme.
 * Automatically logs and visualizes ALL transmission hops across the network in real-time,
 * properly classifying and badging each hop as:
 *  - FORWARDED (⚡ Sky Blue)
 *  - DUPLICATE (⚠️ Amber)
 *  - DELIVERED (🎯 Emerald Green)
 *  - ABORTED   (❌ Coral Red)
 *
 * Clicking any hop centers the map on that node and triggers the transfer visualization.
 */
@SuppressWarnings("serial")
public class PathHopsPanel extends JPanel implements MessageListener {

	// Unified Light Theme Palette
	private static final Color BG_PANEL = new Color(248, 250, 252);        // Slate 50
	private static final Color BG_HEADER = new Color(241, 245, 249);       // Slate 100
	private static final Color BORDER_HEADER = new Color(203, 213, 225);   // Slate 300
	private static final Color BG_CARD = Color.WHITE;                      // Pure White
	private static final Color BG_CARD_HOVER = new Color(241, 245, 249);   // Slate 100 hover
	private static final Color BG_CARD_DELIVERED = new Color(240, 253, 244);// Soft Emerald tint
	private static final Color BORDER_CARD = new Color(226, 232, 240);     // Slate 200 border
	private static final Color BORDER_DELIVERED = new Color(16, 185, 129); // Emerald 500 border

	// Status Colors - High-contrast readable light theme badges
	private static final Color COL_FORWARDED_FG = new Color(2, 132, 199);  // Sky 600
	private static final Color COL_FORWARDED_BG = new Color(224, 242, 254);// Sky 100
	private static final Color COL_FORWARDED_BD = new Color(125, 211, 252);// Sky 300

	private static final Color COL_DUPLICATE_FG = new Color(180, 83, 9);   // Amber 700
	private static final Color COL_DUPLICATE_BG = new Color(254, 243, 199);// Amber 100
	private static final Color COL_DUPLICATE_BD = new Color(252, 211, 77); // Amber 300

	private static final Color COL_DELIVERED_FG = new Color(4, 120, 87);   // Emerald 700
	private static final Color COL_DELIVERED_BG = new Color(209, 250, 229);// Emerald 100
	private static final Color COL_DELIVERED_BD = new Color(110, 231, 183);// Emerald 300

	private static final Color COL_ABORTED_FG = new Color(185, 28, 28);    // Red 700
	private static final Color COL_ABORTED_BG = new Color(254, 226, 226);  // Red 100
	private static final Color COL_ABORTED_BD = new Color(252, 165, 165);  // Red 300

	private static final Color TEXT_PRIMARY = new Color(15, 23, 42);       // Slate 900
	private static final Color TEXT_SECONDARY = new Color(71, 85, 105);    // Slate 600
	private static final Color TEXT_MUTED = new Color(148, 163, 184);      // Slate 400

	private static final Font FONT_HEADER = new Font("SansSerif", Font.BOLD, 12);
	private static final Font FONT_SUB = new Font("SansSerif", Font.PLAIN, 10);
	private static final Font FONT_HOP_TITLE = new Font("SansSerif", Font.BOLD, 11);
	private static final Font FONT_ROUTE = new Font("SansSerif", Font.BOLD, 12);
	private static final Font FONT_PKT = new Font("SansSerif", Font.PLAIN, 10);
	private static final Font FONT_BADGE = new Font("SansSerif", Font.BOLD, 9);
	private static final Font FONT_TIME = new Font("Monospaced", Font.BOLD, 11);

	private DTNSimGUI gui;
	private JPanel cardsContainer;
	private JScrollPane scrollPane;
	private JLabel titleLabel;
	private JLabel subtitleLabel;
	private JLabel countLabel;
	private JTextField searchField;
	private JCheckBox autoScrollCheck;

	// Real-time hop event storage
	private List<HopCardModel> allHops = Collections.synchronizedList(new ArrayList<HopCardModel>());
	private Set<String> nodeReceivedMessages = Collections.synchronizedSet(new HashSet<String>());
	private String filterText = "";
	private String focusedMessageId = null;
	private boolean needsUiRefresh = false;

	public static class HopCardModel {
		int hopNumber;
		double time;
		DTNHost from;
		DTNHost to;
		Message message;
		String status; // FORWARDED, DUPLICATE, DELIVERED, ABORTED

		public HopCardModel(int hopNumber, double time, DTNHost from, DTNHost to, Message m, String status) {
			this.hopNumber = hopNumber;
			this.time = time;
			this.from = from;
			this.to = to;
			this.message = m;
			this.status = status;
		}
	}

	public PathHopsPanel(DTNSimGUI gui) {
		this.gui = gui;
		setLayout(new BorderLayout());
		setBackground(BG_PANEL);
		initUI();

		// Auto refresh timer to batch render incoming hops smoothly
		Timer timer = new Timer(200, new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (needsUiRefresh) {
					rebuildCardsView();
					needsUiRefresh = false;
				}
			}
		});
		timer.start();
	}

	private void initUI() {
		// Header Panel
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBackground(BG_HEADER);
		topPanel.setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, BORDER_HEADER),
				new EmptyBorder(8, 10, 8, 10)));

		titleLabel = new JLabel("MESSAGE TRANSMISSION JOURNEY");
		titleLabel.setFont(FONT_HEADER);
		titleLabel.setForeground(TEXT_PRIMARY);
		topPanel.add(titleLabel);

		subtitleLabel = new JLabel("Click any hop below to center the map on that transfer event.");
		subtitleLabel.setFont(FONT_SUB);
		subtitleLabel.setForeground(TEXT_SECONDARY);
		topPanel.add(subtitleLabel);

		// Toolbar: search filter + controls
		JPanel toolsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		toolsPanel.setBackground(BG_HEADER);
		toolsPanel.setBorder(new EmptyBorder(4, 0, 0, 0));

		JLabel filterLbl = new JLabel("Filter:");
		filterLbl.setFont(FONT_SUB);
		filterLbl.setForeground(TEXT_SECONDARY);
		toolsPanel.add(filterLbl);

		searchField = new JTextField(9);
		searchField.setFont(FONT_SUB);
		searchField.setBackground(Color.WHITE);
		searchField.setForeground(TEXT_PRIMARY);
		searchField.setCaretColor(TEXT_PRIMARY);
		searchField.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225), 1));
		searchField.setToolTipText("Filter by Node (p0, c4) or Message ID (M1)");
		searchField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				filterText = searchField.getText().trim().toLowerCase();
				rebuildCardsView();
			}
		});
		toolsPanel.add(searchField);

		autoScrollCheck = new JCheckBox("Auto-scroll", true);
		autoScrollCheck.setFont(FONT_SUB);
		autoScrollCheck.setBackground(BG_HEADER);
		autoScrollCheck.setForeground(TEXT_SECONDARY);
		toolsPanel.add(autoScrollCheck);

		JButton showAllBtn = new JButton("Show All");
		showAllBtn.setFont(FONT_BADGE);
		showAllBtn.setBackground(Color.WHITE);
		showAllBtn.setForeground(COL_FORWARDED_FG);
		showAllBtn.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(COL_FORWARDED_BD, 1),
				new EmptyBorder(2, 6, 2, 6)));
		showAllBtn.setFocusPainted(false);
		showAllBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
		showAllBtn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				focusedMessageId = null;
				filterText = "";
				searchField.setText("");
				titleLabel.setText("MESSAGE TRANSMISSION JOURNEY");
				subtitleLabel.setText("Click any hop below to center the map on that transfer event.");
				rebuildCardsView();
			}
		});
		toolsPanel.add(showAllBtn);

		countLabel = new JLabel("0 hops");
		countLabel.setFont(FONT_SUB);
		countLabel.setForeground(TEXT_MUTED);
		toolsPanel.add(countLabel);

		topPanel.add(toolsPanel);
		add(topPanel, BorderLayout.NORTH);

		// Cards Container
		cardsContainer = new JPanel();
		cardsContainer.setLayout(new BoxLayout(cardsContainer, BoxLayout.Y_AXIS));
		cardsContainer.setBackground(BG_PANEL);

		scrollPane = new JScrollPane(cardsContainer);
		scrollPane.setBackground(BG_PANEL);
		scrollPane.getViewport().setBackground(BG_PANEL);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		add(scrollPane, BorderLayout.CENTER);
	}

	/**
	 * Rebuilds the visual list of hop cards based on current filters.
	 */
	private void rebuildCardsView() {
		cardsContainer.removeAll();

		List<HopCardModel> copy;
		synchronized (allHops) {
			copy = new ArrayList<HopCardModel>(allHops);
		}

		int visibleCount = 0;
		for (final HopCardModel hop : copy) {
			// Message filter if user focused on one
			if (focusedMessageId != null && hop.message != null && !focusedMessageId.equals(hop.message.getId())) {
				continue;
			}

			// Text filter
			if (!filterText.isEmpty()) {
				String raw = (hop.from + " " + hop.to + " " + (hop.message != null ? hop.message.getId() : "") +
						" " + hop.status + " hop #" + hop.hopNumber).toLowerCase();
				if (!raw.contains(filterText)) {
					continue;
				}
			}

			JPanel card = createHopCard(hop);
			cardsContainer.add(card);
			cardsContainer.add(createSpacer(4));
			visibleCount++;
		}

		countLabel.setText(visibleCount + " / " + copy.size() + " hops");

		cardsContainer.revalidate();
		cardsContainer.repaint();

		if (autoScrollCheck.isSelected()) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					scrollPane.getVerticalScrollBar().setValue(
							scrollPane.getVerticalScrollBar().getMaximum());
				}
			});
		}
	}

	/**
	 * Creates a sleek light card component for a single hop.
	 */
	private JPanel createHopCard(final HopCardModel hop) {
		final boolean isDelivered = "DELIVERED".equals(hop.status);
		final JPanel card = new JPanel(new BorderLayout(8, 2));
		card.setBackground(isDelivered ? BG_CARD_DELIVERED : BG_CARD);
		card.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(isDelivered ? BORDER_DELIVERED : BORDER_CARD, 1),
				new EmptyBorder(8, 10, 8, 10)));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
		card.setCursor(new Cursor(Cursor.HAND_CURSOR));

		// Hover effect
		card.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				card.setBackground(isDelivered ? new Color(220, 252, 231) : BG_CARD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				card.setBackground(isDelivered ? BG_CARD_DELIVERED : BG_CARD);
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				// 1. Focus map on sender/receiver
				if (gui != null && hop.from != null) {
					gui.setFocus(hop.from);
				}
				// 2. Trigger active message transfer visual & path trace
				if (gui != null && gui.getParentFrame() != null && gui.getParentFrame().getPlayField() != null) {
					if (hop.message != null) {
						gui.getParentFrame().getPlayField().showMessagePath(hop.message);
						gui.getParentFrame().getPlayField().addMessageTransfer(hop.from, hop.to, hop.message);
					}
				}
			}
		});

		// Left Column: Icon + Hop # Title, and From ➔ To Route
		JPanel leftCol = new JPanel();
		leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
		leftCol.setOpaque(false);

		// Hop # Header line
		JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		titleLine.setOpaque(false);

		String icon = getHopIcon(hop.status);
		Color iconColor = getStatusFgColor(hop.status);

		JLabel titleLbl = new JLabel(icon + "  Hop #" + hop.hopNumber);
		titleLbl.setFont(FONT_HOP_TITLE);
		titleLbl.setForeground(iconColor);
		titleLine.add(titleLbl);
		leftCol.add(titleLine);

		// Node Route line: Node #1 ➔ Node #5 | Packet 'M1'
		JPanel routeLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		routeLine.setOpaque(false);

		String fromName = formatNodeName(hop.from);
		String toName = formatNodeName(hop.to);
		JLabel routeLbl = new JLabel(fromName + "  ➔  " + toName);
		routeLbl.setFont(FONT_ROUTE);
		routeLbl.setForeground(TEXT_PRIMARY);
		routeLine.add(routeLbl);

		if (hop.message != null) {
			JLabel pktLbl = new JLabel(" | Packet '" + hop.message.getId() + "'");
			pktLbl.setFont(FONT_PKT);
			pktLbl.setForeground(TEXT_SECONDARY);
			routeLine.add(pktLbl);
		}
		leftCol.add(routeLine);

		card.add(leftCol, BorderLayout.CENTER);

		// Right Column: Status Badge & Timestamp
		JPanel rightCol = new JPanel();
		rightCol.setLayout(new BoxLayout(rightCol, BoxLayout.Y_AXIS));
		rightCol.setOpaque(false);

		// Badge panel
		JPanel badgeWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		badgeWrap.setOpaque(false);

		JLabel badgeLbl = new JLabel(" " + hop.status + " ");
		badgeLbl.setFont(FONT_BADGE);
		badgeLbl.setOpaque(true);
		badgeLbl.setBackground(getStatusBgColor(hop.status));
		badgeLbl.setForeground(getStatusFgColor(hop.status));
		badgeLbl.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(getStatusBdColor(hop.status), 1),
				new EmptyBorder(2, 6, 2, 6)));
		badgeWrap.add(badgeLbl);
		rightCol.add(badgeWrap);

		// Timestamp
		JPanel timeWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
		timeWrap.setOpaque(false);
		JLabel timeLbl = new JLabel(String.format("%.1fs", hop.time));
		timeLbl.setFont(FONT_TIME);
		timeLbl.setForeground(TEXT_SECONDARY);
		timeWrap.add(timeLbl);
		rightCol.add(timeWrap);

		card.add(rightCol, BorderLayout.EAST);

		return card;
	}

	private String formatNodeName(DTNHost host) {
		if (host == null) return "Unknown";
		String name = host.toString();
		if (!name.toLowerCase().startsWith("node")) {
			return "Node " + name;
		}
		return name;
	}

	private String getHopIcon(String status) {
		if ("DELIVERED".equals(status)) return "🎯";
		if ("FORWARDED".equals(status)) return "⚡";
		if ("DUPLICATE".equals(status)) return "⚠️";
		if ("ABORTED".equals(status))   return "❌";
		return "⚡";
	}

	private Color getStatusFgColor(String status) {
		if ("DELIVERED".equals(status)) return COL_DELIVERED_FG;
		if ("FORWARDED".equals(status)) return COL_FORWARDED_FG;
		if ("DUPLICATE".equals(status)) return COL_DUPLICATE_FG;
		if ("ABORTED".equals(status))   return COL_ABORTED_FG;
		return COL_FORWARDED_FG;
	}

	private Color getStatusBgColor(String status) {
		if ("DELIVERED".equals(status)) return COL_DELIVERED_BG;
		if ("FORWARDED".equals(status)) return COL_FORWARDED_BG;
		if ("DUPLICATE".equals(status)) return COL_DUPLICATE_BG;
		if ("ABORTED".equals(status))   return COL_ABORTED_BG;
		return COL_FORWARDED_BG;
	}

	private Color getStatusBdColor(String status) {
		if ("DELIVERED".equals(status)) return COL_DELIVERED_BD;
		if ("FORWARDED".equals(status)) return COL_FORWARDED_BD;
		if ("DUPLICATE".equals(status)) return COL_DUPLICATE_BD;
		if ("ABORTED".equals(status))   return COL_ABORTED_BD;
		return COL_FORWARDED_BD;
	}

	private JPanel createSpacer(int height) {
		JPanel spacer = new JPanel();
		spacer.setOpaque(false);
		spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		spacer.setPreferredSize(new Dimension(0, height));
		return spacer;
	}

	/**
	 * Focus on hops for a specific message (e.g. from InfoPanel or EventLog).
	 */
	public void showMessageHops(Message m) {
		if (m == null) return;
		this.focusedMessageId = m.getId();
		titleLabel.setText("MESSAGE TRANSMISSION JOURNEY");
		subtitleLabel.setText("Filtering for Message " + m.getId() + " (" + m.getFrom() + " ➔ " + m.getTo() + ")");
		rebuildCardsView();
	}

	/**
	 * Post-simulation selector.
	 */
	public void showPostSimulationSelector(List<Message> deliveredMessages) {
		titleLabel.setText("POST-SIMULATION PATH VIEWER");
		subtitleLabel.setText("All simulation hops recorded. Click any hop to replay route on map.");
		rebuildCardsView();
	}

	// =========================================================================
	// MessageListener Interface - Real-time Capture
	// =========================================================================

	public void newMessage(Message m) {
		if (m != null && m.getFrom() != null) {
			nodeReceivedMessages.add(m.getFrom().toString() + "_" + m.getId());
		}
	}

	public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean firstDelivery) {
		if (m == null || from == null || to == null) return;

		String key = to.toString() + "_" + m.getId();
		String status;

		if (firstDelivery) {
			status = "DELIVERED";
			nodeReceivedMessages.add(key);
		} else if (to == m.getTo() || nodeReceivedMessages.contains(key)) {
			status = "DUPLICATE";
		} else {
			status = "FORWARDED";
			nodeReceivedMessages.add(key);
		}

		int hopNum = allHops.size() + 1;
		HopCardModel hopModel = new HopCardModel(hopNum, SimClock.getTime(), from, to, m, status);
		allHops.add(hopModel);

		needsUiRefresh = true;
	}

	public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
		if (m == null || from == null || to == null) return;
		int hopNum = allHops.size() + 1;
		HopCardModel hopModel = new HopCardModel(hopNum, SimClock.getTime(), from, to, m, "ABORTED");
		allHops.add(hopModel);

		needsUiRefresh = true;
	}

	public void messageDeleted(Message m, DTNHost where, boolean dropped) {
	}

	public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
	}

	public List<HopCardModel> getAllHops() {
		return allHops;
	}
}
