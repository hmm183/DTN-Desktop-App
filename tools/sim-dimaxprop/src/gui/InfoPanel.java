/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import core.DTNHost;
import core.Message;

/**
 * Modern Information panel that displays detailed node & message status
 * and triggers message hop flow visualization on the playfield.
 */
@SuppressWarnings("serial")
public class InfoPanel extends JPanel implements ActionListener {

	private static final Color BG_INFO = new Color(245, 248, 252);
	private static final Color BORDER_COLOR = new Color(215, 225, 235);
	private static final Color COL_ACCENT = new Color(41, 128, 185);

	private static final Font FONT_TITLE = new Font("SansSerif", Font.BOLD, 11);
	private static final Font FONT_TEXT = new Font("SansSerif", Font.PLAIN, 11);
	private static final Font FONT_BTN = new Font("SansSerif", Font.BOLD, 10);

	private JComboBox<Object> msgChooser;
	private JLabel info;
	private JButton tracePathButton;
	private JButton clearPathButton;
	private JButton routingInfoButton;
	private Message selectedMessage;
	private DTNHost selectedHost;
	private DTNSimGUI gui;

	public InfoPanel(DTNSimGUI gui) {
		this.gui = gui;
		setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));
		setBackground(BG_INFO);
		setBorder(new CompoundBorder(
				new MatteBorder(1, 0, 1, 0, BORDER_COLOR),
				new EmptyBorder(3, 8, 3, 8)));
		reset();
	}

	private void reset() {
		this.removeAll();
		this.info = null;
		this.tracePathButton = null;
		this.clearPathButton = null;
		this.selectedMessage = null;
		this.selectedHost = null;
		this.revalidate();
		this.repaint();
	}

	public void showInfo(DTNHost host) {
		Vector<Object> messages = new Vector<Object>();
		List<Message> hostMsgs = new Vector<Message>(host.getMessageCollection());
		Collections.sort(hostMsgs);

		reset();
		this.selectedHost = host;

		JLabel titleLbl = new JLabel("Host: " + host.toString());
		titleLbl.setFont(FONT_TITLE);
		titleLbl.setForeground(COL_ACCENT);
		this.add(titleLbl);

		String statusTxt = String.format("Loc: (%.1f, %.1f) | Buffer: %d msgs",
				host.getLocation().getX(), host.getLocation().getY(), host.getNrofMessages());
		JLabel locLbl = new JLabel(statusTxt);
		locLbl.setFont(FONT_TEXT);
		this.add(locLbl);

		messages.add(hostMsgs.size() + " messages carried");
		messages.addAll(hostMsgs);
		msgChooser = new JComboBox<Object>(messages);
		msgChooser.setFont(FONT_TEXT);
		msgChooser.setSelectedIndex(0);
		msgChooser.addActionListener(this);
		this.add(msgChooser);

		routingInfoButton = new JButton("Routing Info");
		routingInfoButton.setFont(FONT_BTN);
		routingInfoButton.setMargin(new Insets(2, 6, 2, 6));
		routingInfoButton.addActionListener(this);
		this.add(routingInfoButton);

		this.revalidate();
		this.repaint();
	}

	public void showInfo(Message message) {
		reset();
		this.selectedMessage = message;

		JLabel titleLbl = new JLabel("Message: " + message.getId());
		titleLbl.setFont(FONT_TITLE);
		titleLbl.setForeground(new Color(142, 68, 173));
		this.add(titleLbl);

		setMessageInfo(message);
		this.revalidate();
		this.repaint();
	}

	private void setMessageInfo(Message m) {
		this.selectedMessage = m;
		int ttl = m.getTtl();
		String pathHops = (m.getHops().size() - 1) + " hops " + m.getHops();
		String txt = String.format(" [%s -> %s] Size: %d KB | Recv @ %.1fs%s | Path: %s",
				m.getFrom(), m.getTo(), m.getSize() / 1024, m.getReceiveTime(),
				(ttl != Integer.MAX_VALUE ? " | TTL: " + ttl : ""), pathHops);

		if (this.info == null) {
			this.info = new JLabel(txt);
			this.info.setFont(FONT_TEXT);
			this.add(info);

			this.tracePathButton = new JButton("Trace Hop Path on Map");
			this.tracePathButton.setFont(FONT_BTN);
			this.tracePathButton.setForeground(Color.WHITE);
			this.tracePathButton.setBackground(new Color(41, 128, 185));
			this.tracePathButton.setMargin(new Insets(2, 8, 2, 8));
			this.tracePathButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
			this.tracePathButton.addActionListener(this);
			this.add(tracePathButton);

			this.clearPathButton = new JButton("Clear Path");
			this.clearPathButton.setFont(FONT_BTN);
			this.clearPathButton.setMargin(new Insets(2, 6, 2, 6));
			this.clearPathButton.addActionListener(this);
			this.add(clearPathButton);
		} else {
			this.info.setText(txt);
		}

		this.revalidate();
	}

	public void actionPerformed(ActionEvent e) {
		if (e.getSource() == msgChooser) {
			if (msgChooser.getSelectedIndex() <= 0) {
				return;
			}
			Message m = (Message) msgChooser.getSelectedItem();
			showInfo(m);
			if (gui != null && gui.getParentFrame() != null && gui.getParentFrame().getPlayField() != null) {
				gui.getParentFrame().getPlayField().showMessagePath(m);
				gui.getParentFrame().getPathHopsPanel().showMessageHops(m);
				gui.getParentFrame().showPathHopsTab();
			}
		} else if (e.getSource() == this.tracePathButton) {
			if (this.selectedMessage != null && gui != null && gui.getParentFrame() != null) {
				gui.getParentFrame().getPlayField().showMessagePath(this.selectedMessage);
				gui.getParentFrame().getPathHopsPanel().showMessageHops(this.selectedMessage);
				gui.getParentFrame().showPathHopsTab();
			}
		} else if (e.getSource() == this.clearPathButton) {
			if (gui != null && gui.getParentFrame() != null) {
				gui.getParentFrame().getPlayField().clearMessagePath();
			}
		} else if (e.getSource() == this.routingInfoButton) {
			if (this.selectedHost != null) {
				new RoutingInfoWindow(this.selectedHost);
			}
		}
	}
}
