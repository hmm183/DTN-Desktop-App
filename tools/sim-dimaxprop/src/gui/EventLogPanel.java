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
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import core.ConnectionListener;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;
import core.SimClock;

/**
 * Modernized, highly readable Event Log Panel matching ONE Simulator's clean light theme.
 * Includes an integrated, full-width top Selections Bar for event filtering and pausing,
 * eliminating the need to resize or adjust window splitters to choose selections.
 */
@SuppressWarnings("serial")
public class EventLogPanel extends JPanel
		implements ConnectionListener, MessageListener, ActionListener {

	public static final String EL_PANEL_NS = "GUI.EventLogPanel";
	public static final String NROF_EVENTS_S = "nrofEvents";
	public static final String EVENTS_RE_S = "REfilter";

	public static final int DEFAULT_MAX_NROF_EVENTS = 2000;
	public static final int LOG_UP_INTERVAL = 200;

	// Unified Light Theme Palette
	private static final Color BG_HEADER = new Color(241, 245, 249);       // Slate 100
	private static final Color BG_SELECTIONS = new Color(248, 250, 252);   // Slate 50
	private static final Color BG_ROW_EVEN = Color.WHITE;                  // Pure White
	private static final Color BG_ROW_ODD = new Color(248, 250, 253);      // Subtle tint
	private static final Color BG_ROW_HIGHLIGHT = new Color(254, 249, 231);// Soft amber highlight
	private static final Color BORDER_ROW = new Color(226, 232, 240);      // Slate 200
	private static final Color BORDER_HEADER = new Color(203, 213, 225);   // Slate 300

	// Status Colors - Light theme badges
	private static final Color COL_CON_UP = new Color(16, 185, 129);       // Emerald Green
	private static final Color COL_CON_DOWN = new Color(100, 116, 139);    // Slate Gray
	private static final Color COL_MSG_CREATE = new Color(147, 51, 234);   // Purple
	private static final Color COL_FORWARDED = new Color(14, 165, 233);    // Sky Blue
	private static final Color COL_DUPLICATE = new Color(245, 158, 11);    // Amber
	private static final Color COL_DELIVERED = new Color(16, 185, 129);    // Bright Green
	private static final Color COL_ABORTED = new Color(239, 68, 68);       // Crimson Red
	private static final Color COL_MSG_DROP = new Color(220, 38, 38);      // Dark Red
	private static final Color COL_MSG_REMOVE = new Color(71, 85, 105);    // Dark Slate

	private static final Font FONT_TIME = new Font("Monospaced", Font.BOLD, 11);
	private static final Font FONT_BADGE = new Font("SansSerif", Font.BOLD, 9);
	private static final Font FONT_TEXT = new Font("SansSerif", Font.PLAIN, 11);
	private static final Font FONT_BTN = new Font("SansSerif", Font.BOLD, 10);
	private static final Font FONT_SECTION = new Font("SansSerif", Font.BOLD, 10);

	private String regExp = null;
	private int maxNrofEvents;

	private DTNSimGUI gui;
	private List<LogEntry> allEntries;
	private Set<String> nodeReceivedMessages;
	private JPanel listContainer;
	private JScrollPane scrollPane;
	private JTextField searchField;
	private JLabel countLabel;
	private JCheckBox autoScrollCheck;

	// Integrated Selections Bar Components
	private JPanel selectionsPanel;
	private JButton toggleSelectionsBtn;
	private boolean selectionsExpanded = true;
	private JCheckBox pauseOnEventMasterCheck;

	private JCheckBox cbLinkUp;
	private JCheckBox cbLinkDown;
	private JCheckBox cbCreated;
	private JCheckBox cbForwarded;
	private JCheckBox cbDuplicate;
	private JCheckBox cbDelivered;
	private JCheckBox cbAborted;
	private JCheckBox cbDropped;
	private JCheckBox cbRemoved;

	private EventLogControlPanel controls;
	private EventLogControl conUpCheck;
	private EventLogControl conDownCheck;
	private EventLogControl msgCreateCheck;
	private EventLogControl msgForwardCheck;
	private EventLogControl msgDuplicateCheck;
	private EventLogControl msgDeliveredCheck;
	private EventLogControl msgRemoveCheck;
	private EventLogControl msgDropCheck;
	private EventLogControl msgAbortCheck;

	private boolean needsUiUpdate = false;

	public EventLogPanel(DTNSimGUI gui) {
		this.gui = gui;
		Settings s = new Settings(EL_PANEL_NS);
		this.maxNrofEvents = s.getInt(NROF_EVENTS_S, DEFAULT_MAX_NROF_EVENTS);
		this.regExp = s.getSetting(EVENTS_RE_S, null);
		this.allEntries = Collections.synchronizedList(new ArrayList<LogEntry>());
		this.nodeReceivedMessages = Collections.synchronizedSet(new HashSet<String>());

		setLayout(new BorderLayout());
		setBackground(BG_SELECTIONS);

		this.controls = createControls();
		initUI();

		Timer t = new Timer(LOG_UP_INTERVAL, new ActionListener() {
			public void actionPerformed(ActionEvent evt) {
				if (needsUiUpdate) {
					updateLogView();
					needsUiUpdate = false;
				}
			}
		});
		t.start();
	}

	private EventLogControlPanel createControls() {
		EventLogControlPanel c = new EventLogControlPanel();
		conUpCheck = c.addControl("LINK UP", true, false);
		conDownCheck = c.addControl("LINK DOWN", true, false);
		msgCreateCheck = c.addControl("CREATED", true, false);
		msgForwardCheck = c.addControl("FORWARDED", true, false);
		msgDuplicateCheck = c.addControl("DUPLICATE", true, false);
		msgDeliveredCheck = c.addControl("DELIVERED", true, false);
		msgAbortCheck = c.addControl("ABORTED", true, false);
		msgDropCheck = c.addControl("DROPPED", true, false);
		msgRemoveCheck = c.addControl("REMOVED", true, false);
		return c;
	}

	public EventLogControlPanel getControls() {
		return this.controls;
	}

	private void initUI() {
		// Top container holding Header Bar + Selections Bar
		JPanel topContainer = new JPanel();
		topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
		topContainer.setBackground(BG_HEADER);

		// 1. Header Toolbar
		JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		headerPanel.setBackground(BG_HEADER);
		headerPanel.setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, BORDER_HEADER),
				new EmptyBorder(2, 4, 2, 4)));

		JLabel searchLbl = new JLabel("Filter:");
		searchLbl.setFont(FONT_TEXT);
		searchLbl.setForeground(new Color(15, 23, 42));
		headerPanel.add(searchLbl);

		searchField = new JTextField(9);
		searchField.setFont(FONT_TEXT);
		searchField.setBackground(Color.WHITE);
		searchField.setForeground(new Color(15, 23, 42));
		searchField.setBorder(BorderFactory.createLineBorder(BORDER_ROW, 1));
		searchField.setToolTipText("Filter by node name (p0, c4), message ID (M1), or status (DELIVERED, FORWARDED)");
		searchField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				updateLogView();
			}
		});
		headerPanel.add(searchField);

		autoScrollCheck = new JCheckBox("Auto-scroll", true);
		autoScrollCheck.setFont(FONT_BTN);
		autoScrollCheck.setBackground(BG_HEADER);
		autoScrollCheck.setForeground(new Color(71, 85, 105));
		headerPanel.add(autoScrollCheck);

		// Toggle button for Selections bar
		toggleSelectionsBtn = new JButton("Filter Selections ▲");
		toggleSelectionsBtn.setFont(FONT_BTN);
		toggleSelectionsBtn.setBackground(Color.WHITE);
		toggleSelectionsBtn.setForeground(new Color(2, 132, 199));
		toggleSelectionsBtn.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(new Color(125, 211, 252), 1),
				new EmptyBorder(2, 6, 2, 6)));
		toggleSelectionsBtn.setFocusPainted(false);
		toggleSelectionsBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
		toggleSelectionsBtn.setToolTipText("Toggle the Event Selections & Filters panel");
		toggleSelectionsBtn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				selectionsExpanded = !selectionsExpanded;
				selectionsPanel.setVisible(selectionsExpanded);
				toggleSelectionsBtn.setText(selectionsExpanded ? "Filter Selections ▲" : "Filter Selections ▼");
				revalidate();
				repaint();
			}
		});
		headerPanel.add(toggleSelectionsBtn);

		JButton clearBtn = new JButton("Clear Log");
		clearBtn.setFont(FONT_BTN);
		clearBtn.setBackground(Color.WHITE);
		clearBtn.setForeground(new Color(71, 85, 105));
		clearBtn.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(BORDER_ROW, 1),
				new EmptyBorder(2, 6, 2, 6)));
		clearBtn.setFocusPainted(false);
		clearBtn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				allEntries.clear();
				updateLogView();
			}
		});
		headerPanel.add(clearBtn);

		JButton saveLogBtn = new JButton("Save Log");
		saveLogBtn.setFont(FONT_BTN);
		saveLogBtn.setBackground(Color.WHITE);
		saveLogBtn.setForeground(new Color(16, 185, 129));
		saveLogBtn.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(new Color(110, 231, 183), 1),
				new EmptyBorder(2, 6, 2, 6)));
		saveLogBtn.setFocusPainted(false);
		saveLogBtn.setToolTipText("Save all event log entries to a text file in reports/");
		saveLogBtn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				saveLogToFile();
			}
		});
		headerPanel.add(saveLogBtn);

		countLabel = new JLabel("0 events");
		countLabel.setFont(FONT_TEXT);
		countLabel.setForeground(new Color(100, 116, 139));
		headerPanel.add(countLabel);

		topContainer.add(headerPanel);

		// 2. Integrated Selections Bar
		selectionsPanel = buildSelectionsPanel();
		topContainer.add(selectionsPanel);

		add(topContainer, BorderLayout.NORTH);

		// 3. Log rows list container
		listContainer = new JPanel();
		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
		listContainer.setBackground(Color.WHITE);

		scrollPane = new JScrollPane(listContainer);
		scrollPane.setBackground(Color.WHITE);
		scrollPane.getViewport().setBackground(Color.WHITE);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(scrollPane, BorderLayout.CENTER);
	}

	/**
	 * Builds the integrated Selections Bar with clearly readable checkboxes.
	 */
	private JPanel buildSelectionsPanel() {
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(BG_SELECTIONS);
		card.setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, BORDER_ROW),
				new EmptyBorder(4, 8, 6, 8)));

		// Row 1: Actions & Pause Master Check
		JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		actionRow.setOpaque(false);

		JLabel titleLbl = new JLabel("CHOOSE EVENT SELECTIONS:");
		titleLbl.setFont(FONT_SECTION);
		titleLbl.setForeground(new Color(30, 41, 59));
		actionRow.add(titleLbl);

		JButton selectAllBtn = new JButton("Select All");
		selectAllBtn.setFont(FONT_BADGE);
		selectAllBtn.setBackground(Color.WHITE);
		selectAllBtn.setForeground(new Color(2, 132, 199));
		selectAllBtn.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(new Color(125, 211, 252), 1),
				new EmptyBorder(1, 5, 1, 5)));
		selectAllBtn.setFocusPainted(false);
		selectAllBtn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				setAllSelections(true);
			}
		});
		actionRow.add(selectAllBtn);

		JButton clearAllBtn = new JButton("Clear All");
		clearAllBtn.setFont(FONT_BADGE);
		clearAllBtn.setBackground(Color.WHITE);
		clearAllBtn.setForeground(new Color(100, 116, 139));
		clearAllBtn.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(BORDER_ROW, 1),
				new EmptyBorder(1, 5, 1, 5)));
		clearAllBtn.setFocusPainted(false);
		clearAllBtn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				setAllSelections(false);
			}
		});
		actionRow.add(clearAllBtn);

		pauseOnEventMasterCheck = new JCheckBox("Pause on Events", false);
		pauseOnEventMasterCheck.setFont(FONT_BTN);
		pauseOnEventMasterCheck.setOpaque(false);
		pauseOnEventMasterCheck.setForeground(new Color(71, 85, 105));
		pauseOnEventMasterCheck.setToolTipText("If checked, incoming events will pause the simulation for inspection");
		pauseOnEventMasterCheck.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				boolean p = pauseOnEventMasterCheck.isSelected();
				conUpCheck.setPauseOnEvent(p);
				conDownCheck.setPauseOnEvent(p);
				msgCreateCheck.setPauseOnEvent(p);
				msgForwardCheck.setPauseOnEvent(p);
				msgDuplicateCheck.setPauseOnEvent(p);
				msgDeliveredCheck.setPauseOnEvent(p);
				msgAbortCheck.setPauseOnEvent(p);
				msgDropCheck.setPauseOnEvent(p);
				msgRemoveCheck.setPauseOnEvent(p);
			}
		});
		actionRow.add(pauseOnEventMasterCheck);

		card.add(actionRow);

		// Row 2: Checkboxes Flow (Connections & Messages)
		JPanel checksRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		checksRow.setOpaque(false);

		// Connections
		cbLinkUp = createSelectionBox("LINK UP", COL_CON_UP, conUpCheck);
		cbLinkDown = createSelectionBox("LINK DOWN", COL_CON_DOWN, conDownCheck);
		checksRow.add(cbLinkUp);
		checksRow.add(cbLinkDown);

		JLabel sep = new JLabel(" | ");
		sep.setForeground(new Color(203, 213, 225));
		checksRow.add(sep);

		// Messages
		cbCreated = createSelectionBox("CREATED", COL_MSG_CREATE, msgCreateCheck);
		cbForwarded = createSelectionBox("FORWARDED", COL_FORWARDED, msgForwardCheck);
		cbDuplicate = createSelectionBox("DUPLICATE", COL_DUPLICATE, msgDuplicateCheck);
		cbDelivered = createSelectionBox("DELIVERED", COL_DELIVERED, msgDeliveredCheck);
		cbAborted = createSelectionBox("ABORTED", COL_ABORTED, msgAbortCheck);
		cbDropped = createSelectionBox("DROPPED", COL_MSG_DROP, msgDropCheck);
		cbRemoved = createSelectionBox("REMOVED", COL_MSG_REMOVE, msgRemoveCheck);

		checksRow.add(cbCreated);
		checksRow.add(cbForwarded);
		checksRow.add(cbDuplicate);
		checksRow.add(cbDelivered);
		checksRow.add(cbAborted);
		checksRow.add(cbDropped);
		checksRow.add(cbRemoved);

		card.add(checksRow);

		return card;
	}

	private JCheckBox createSelectionBox(final String labelText, final Color color, final EventLogControl control) {
		final JCheckBox box = new JCheckBox(labelText, true);
		box.setFont(FONT_BADGE);
		box.setOpaque(false);
		box.setForeground(color);
		box.setFocusPainted(false);
		box.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				control.setShowEvent(box.isSelected());
				updateLogView();
			}
		});
		return box;
	}

	private void setAllSelections(boolean selected) {
		cbLinkUp.setSelected(selected); conUpCheck.setShowEvent(selected);
		cbLinkDown.setSelected(selected); conDownCheck.setShowEvent(selected);
		cbCreated.setSelected(selected); msgCreateCheck.setShowEvent(selected);
		cbForwarded.setSelected(selected); msgForwardCheck.setShowEvent(selected);
		cbDuplicate.setSelected(selected); msgDuplicateCheck.setShowEvent(selected);
		cbDelivered.setSelected(selected); msgDeliveredCheck.setShowEvent(selected);
		cbAborted.setSelected(selected); msgAbortCheck.setShowEvent(selected);
		cbDropped.setSelected(selected); msgDropCheck.setShowEvent(selected);
		cbRemoved.setSelected(selected); msgRemoveCheck.setShowEvent(selected);
		updateLogView();
	}

	public static class LogEntry {
		double time;
		String eventType;
		Color badgeColor;
		DTNHost host1;
		DTNHost host2;
		Message message;
		String formattedText;
		boolean highlight;
		String rawText;

		LogEntry(double time, String eventType, Color badgeColor, DTNHost host1,
				DTNHost host2, Message message, String formattedText, boolean highlight) {
			this.time = time;
			this.eventType = eventType;
			this.badgeColor = badgeColor;
			this.host1 = host1;
			this.host2 = host2;
			this.message = message;
			this.formattedText = formattedText;
			this.highlight = highlight;
			this.rawText = (eventType + " " + formattedText + " " +
					(host1 != null ? host1 : "") + " " +
					(host2 != null ? host2 : "") + " " +
					(message != null ? message.getId() : "")).toLowerCase();
		}
	}

	private void addEvent(String eventType, Color badgeColor, DTNHost host1,
			DTNHost host2, Message message, String formattedText, boolean highlight) {
		LogEntry entry = new LogEntry(SimClock.getTime(), eventType, badgeColor,
				host1, host2, message, formattedText, highlight);

		synchronized (allEntries) {
			allEntries.add(entry);
			if (allEntries.size() > maxNrofEvents) {
				allEntries.remove(0);
			}
		}
		needsUiUpdate = true;
	}

	private boolean isEventTypeVisible(String type) {
		if ("LINK UP".equals(type)) return conUpCheck.showEvent();
		if ("LINK DOWN".equals(type)) return conDownCheck.showEvent();
		if ("CREATED".equals(type)) return msgCreateCheck.showEvent();
		if ("FORWARDED".equals(type)) return msgForwardCheck.showEvent();
		if ("DUPLICATE".equals(type)) return msgDuplicateCheck.showEvent();
		if ("DELIVERED".equals(type)) return msgDeliveredCheck.showEvent();
		if ("ABORTED".equals(type)) return msgAbortCheck.showEvent();
		if ("DROPPED".equals(type)) return msgDropCheck.showEvent();
		if ("REMOVED".equals(type)) return msgRemoveCheck.showEvent();
		return true;
	}

	private void updateLogView() {
		listContainer.removeAll();
		String filter = searchField.getText().trim().toLowerCase();

		List<LogEntry> copy;
		synchronized (allEntries) {
			copy = new ArrayList<LogEntry>(allEntries);
		}

		int count = 0;
		for (int i = 0; i < copy.size(); i++) {
			LogEntry entry = copy.get(i);
			// Check if event type is toggled ON
			if (!isEventTypeVisible(entry.eventType)) {
				continue;
			}
			// Check text filter
			if (!filter.isEmpty() && !entry.rawText.contains(filter)) {
				continue;
			}
			JPanel row = createRowPanel(entry, count % 2 == 0);
			listContainer.add(row);
			count++;
		}

		countLabel.setText(count + " / " + copy.size() + " events");
		listContainer.revalidate();
		listContainer.repaint();

		if (autoScrollCheck.isSelected()) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					scrollPane.getVerticalScrollBar().setValue(
							scrollPane.getVerticalScrollBar().getMaximum());
				}
			});
		}
	}

	private JPanel createRowPanel(final LogEntry entry, boolean isEven) {
		final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		Color bg = entry.highlight ? BG_ROW_HIGHLIGHT : (isEven ? BG_ROW_EVEN : BG_ROW_ODD);
		row.setBackground(bg);
		row.setBorder(new MatteBorder(0, 0, 1, 0, BORDER_ROW));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		// 1. Timestamp
		JLabel timeLbl = new JLabel(String.format("[% 7.1fs]", entry.time));
		timeLbl.setFont(FONT_TIME);
		timeLbl.setForeground(new Color(100, 115, 130));
		row.add(timeLbl);

		// 2. Status Badge
		JLabel badge = createBadgeLabel(entry.eventType, entry.badgeColor);
		row.add(badge);

		// 3. Formatted Content line
		JLabel descLbl = new JLabel(entry.formattedText);
		descLbl.setFont(FONT_TEXT);
		descLbl.setForeground(new Color(15, 23, 42));
		row.add(descLbl);

		// 4. Quick Action Buttons if message present
		if (entry.message != null) {
			JButton traceBtn = createTraceButton(entry.message);
			row.add(traceBtn);
		}

		return row;
	}

	private JLabel createBadgeLabel(String text, Color color) {
		JLabel label = new JLabel(" " + text + " ");
		label.setFont(FONT_BADGE);
		label.setOpaque(true);
		label.setBackground(color);
		label.setForeground(Color.WHITE);
		label.setBorder(new EmptyBorder(1, 5, 1, 5));
		return label;
	}

	private JButton createTraceButton(final Message m) {
		JButton btn = new JButton("Trace Path");
		btn.setFont(FONT_BTN);
		btn.setForeground(Color.WHITE);
		btn.setBackground(new Color(2, 132, 199));
		btn.setBorder(new EmptyBorder(1, 6, 1, 6));
		btn.setFocusPainted(false);
		btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
		btn.setToolTipText("Highlight path for " + m.getId() + " on map & hops tab");

		btn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (gui != null && gui.getParentFrame() != null) {
					gui.getParentFrame().getPlayField().showMessagePath(m);
					gui.getParentFrame().getPathHopsPanel().showMessageHops(m);
					gui.getParentFrame().showPathHopsTab();
				}
			}
		});
		return btn;
	}

	private void processEvent(EventLogControl check, String eventType, Color badgeColor,
			DTNHost host1, DTNHost host2, Message message, String formattedText) {
		if (regExp != null && !formattedText.matches(regExp)) {
			return;
		}

		if (check != null && check.pauseOnEvent()) {
			gui.setPaused(true);
			if (host1 != null) {
				gui.setFocus(host1);
			}
		}

		addEvent(eventType, badgeColor, host1, host2, message, formattedText,
				check != null && check.pauseOnEvent());
	}

	// =========================================================================
	// Connection & Message Listener Implementations
	// =========================================================================

	public void hostsConnected(DTNHost host1, DTNHost host2) {
		String text = host1 + "  <->  " + host2 + " (LINK UP)";
		processEvent(conUpCheck, "LINK UP", COL_CON_UP, host1, host2, null, text);
	}

	public void hostsDisconnected(DTNHost host1, DTNHost host2) {
		String text = host1 + "  -x-  " + host2 + " (LINK DOWN)";
		processEvent(conDownCheck, "LINK DOWN", COL_CON_DOWN, host1, host2, null, text);
	}

	public void newMessage(Message m) {
		if (m == null) return;
		nodeReceivedMessages.add(m.getFrom().toString() + "_" + m.getId());
		String text = "Message '" + m.getId() + "' generated at Source " + m.getFrom() +
				" targeting Destination " + m.getTo() + " (" + (m.getSize() / 1024) + " KB)";
		processEvent(msgCreateCheck, "CREATED", COL_MSG_CREATE, m.getFrom(), m.getTo(), m, text);
	}

	public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean firstDelivery) {
		if (m == null || from == null || to == null) return;

		String key = to.toString() + "_" + m.getId();
		String status;
		Color color;
		EventLogControl control;

		if (firstDelivery) {
			status = "DELIVERED";
			color = COL_DELIVERED;
			control = msgDeliveredCheck;
			nodeReceivedMessages.add(key);
		} else if (to == m.getTo() || nodeReceivedMessages.contains(key)) {
			status = "DUPLICATE";
			color = COL_DUPLICATE;
			control = msgDuplicateCheck;
		} else {
			status = "FORWARDED";
			color = COL_FORWARDED;
			control = msgForwardCheck;
			nodeReceivedMessages.add(key);
		}

		String text = from + "  ➔  " + to + " | Packet '" + m.getId() + "' (" + status + ")";
		processEvent(control, status, color, from, to, m, text);
	}

	public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
		String text = from + "  ➔  " + to + " | Packet '" + (m != null ? m.getId() : "null") + "' (ABORTED)";
		processEvent(msgAbortCheck, "ABORTED", COL_ABORTED, from, to, m, text);
	}

	public void messageDeleted(Message m, DTNHost where, boolean dropped) {
		if (!dropped) {
			String text = "Message '" + (m != null ? m.getId() : "null") + "' removed from " + where;
			processEvent(msgRemoveCheck, "REMOVED", COL_MSG_REMOVE, where, null, m, text);
		} else {
			String text = "Message '" + (m != null ? m.getId() : "null") + "' dropped at " + where;
			processEvent(msgDropCheck, "DROPPED", COL_MSG_DROP, where, null, m, text);
		}
	}

	public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
		if (gui != null && gui.getParentFrame() != null && gui.getParentFrame().getPlayField() != null) {
			gui.getParentFrame().getPlayField().addMessageTransfer(from, to, m);
		}
	}

	// =========================================================================
	// Log Persistence
	// =========================================================================

	private void saveLogToFile() {
		try {
			File dir = new File("reports");
			if (!dir.exists()) dir.mkdirs();

			String timestamp = String.format("%.0f", SimClock.getTime());
			File file = new File(dir, "EventLog_t" + timestamp + ".txt");

			PrintWriter pw = new PrintWriter(new FileWriter(file));
			pw.println("============================================================");
			pw.println("                 ONE SIMULATOR EVENT LOG");
			pw.println("============================================================");
			pw.println("Sim Time    : " + SimClock.getTime() + "s");
			pw.println("Total Events: " + allEntries.size());
			pw.println("============================================================");
			pw.println();

			List<LogEntry> copy;
			synchronized (allEntries) {
				copy = new ArrayList<LogEntry>(allEntries);
			}

			for (LogEntry entry : copy) {
				pw.println(String.format("[%8.1fs] %-12s %s",
						entry.time,
						"(" + entry.eventType + ")",
						entry.formattedText));
			}

			pw.close();

			JOptionPane.showMessageDialog(this,
					"Log successfully saved to:\n" + file.getAbsolutePath() + "\n(" + copy.size() + " events)",
					"Log Saved", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					"Failed to save log: " + ex.getMessage(),
					"Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	public void actionPerformed(ActionEvent e) {
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + " with " + allEntries.size() + " events";
	}
}
