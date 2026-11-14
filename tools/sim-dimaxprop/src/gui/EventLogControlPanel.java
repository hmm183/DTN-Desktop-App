/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Vector;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Control panel for event log filtering and pausing.
 */
@SuppressWarnings("serial")
public class EventLogControlPanel extends JPanel implements ActionListener {
	private static final String TITLE_TEXT = "Event Filters";
	private static final String SHOW_TEXT = "Show";
	private static final String PAUSE_TEXT = "Pause";
	private Font smallFont = new Font("SansSerif", Font.PLAIN, 11);
	private Font headingFont = new Font("SansSerif", Font.BOLD, 11);
	private Vector<EventLogControl> logControls;

	private JCheckBox showAllCheck;
	private JCheckBox pauseAllCheck;

	private GridBagLayout layout;
	private GridBagConstraints c;

	/**
	 * Constructor. Creates a new control panel.
	 */
	public EventLogControlPanel() {
		layout = new GridBagLayout();
		c = new GridBagConstraints();
		logControls = new Vector<EventLogControl>();

		c.insets = new Insets(2, 4, 2, 4);

		setLayout(layout);
		setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(new Color(200, 210, 220), 1),
				TITLE_TEXT));

		c.fill = GridBagConstraints.BOTH;
		addLabel(" ");
		addLabel(SHOW_TEXT);
		c.gridwidth = GridBagConstraints.REMAINDER;
		addLabel(PAUSE_TEXT);

		// "all" selections
		c.gridwidth = 1;
		JLabel allLbl = addLabel("All");
		allLbl.setFont(headingFont);
		showAllCheck = addCheckBox(true, false);
		pauseAllCheck = addCheckBox(false, true);
		showAllCheck.addActionListener(this);
		pauseAllCheck.addActionListener(this);

		setMinimumSize(new Dimension(160, 0));
	}

	public EventLogControl addControl(String name, boolean showOn, boolean pauseOn) {
		JCheckBox filterCheck;
		JCheckBox pauseCheck;
		EventLogControl control;

		c.gridwidth = 1;
		addLabel(name);
		filterCheck = addCheckBox(showOn, false);
		pauseCheck = addCheckBox(pauseOn, true);

		control = new EventLogControl(filterCheck, pauseCheck);
		this.logControls.add(control);
		return control;
	}

	private JCheckBox addCheckBox(boolean selected, boolean endOfRow) {
		JCheckBox box = new JCheckBox();
		box.setSelected(selected);

		if (endOfRow) {
			c.gridwidth = GridBagConstraints.REMAINDER;
		} else {
			c.gridwidth = 1;
		}

		layout.setConstraints(box, c);
		add(box);
		return box;
	}

	public EventLogControl addControl(String name) {
		return addControl(name, true, false);
	}

	public void addHeading(String name) {
		c.gridwidth = GridBagConstraints.REMAINDER;
		JLabel lbl = addLabel(name);
		lbl.setFont(this.headingFont);
		lbl.setForeground(new Color(41, 128, 185));
	}

	private JLabel addLabel(String txt) {
		JLabel label = new JLabel(txt);
		label.setFont(this.smallFont);
		layout.setConstraints(label, c);
		add(label);
		return label;
	}

	public void actionPerformed(ActionEvent e) {
		if (e.getSource() == this.showAllCheck) {
			for (EventLogControl elc : logControls) {
				elc.setShowEvent(this.showAllCheck.isSelected());
			}
		} else if (e.getSource() == this.pauseAllCheck) {
			for (EventLogControl elc : logControls) {
				elc.setPauseOnEvent(this.pauseAllCheck.isSelected());
			}
		}
	}
}
