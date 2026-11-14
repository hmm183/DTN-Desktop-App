/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import gui.playfield.PlayField;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import core.Coord;
import core.SimClock;

/**
 * Modernized GUI Control Panel.
 */
@SuppressWarnings("serial")
public class GUIControls extends JPanel implements ActionListener, ChangeListener {
	private static final String PATH_GRAPHICS = "buttonGraphics/";
	private static final String ICON_PAUSE = "Pause16.gif";
	private static final String ICON_PLAY = "Play16.gif";
	private static final String ICON_ZOOM = "Zoom24.gif";
	private static final String ICON_STEP = "StepForward16.gif";
	private static final String ICON_FFW = "FastForward16.gif";

	private static final String TEXT_PAUSE = "Pause simulation";
	private static final String TEXT_PLAY = "Play simulation";
	private static final String TEXT_PLAY_UNTIL = "Play simulation until sim time...";
	private static final String TEXT_STEP = "Step forward one interval";
	private static final String TEXT_FFW = "Enable/disable fast forward";
	private static final String TEXT_UP_CHOOSER = "Update:";
	private static final String TEXT_SCREEN_SHOT = "Screenshot";
	private static final String TEXT_SIMTIME = "Simulation time (click to force update, right click for HH:MM:SS)";
	private static final String TEXT_SEPS = "Simulated seconds per second";

	private static final Font FONT_TIME = new Font("Monospaced", Font.BOLD, 13);
	private static final Font FONT_TEXT = new Font("SansSerif", Font.PLAIN, 11);
	private static final Font FONT_BTN = new Font("SansSerif", Font.BOLD, 11);

	private static final int EPS_AVG_TIME = 2000;
	private static final String SCREENSHOT_FILE_TYPE = "png";
	private static final String SCREENSHOT_FILE = "screenshot";

	private JTextField simTimeField;
	private JLabel sepsField;
	private JButton playButton;
	private JButton playUntilButton;
	private boolean paused;
	private JButton stepButton;
	private boolean step;
	private JButton ffwButton;
	private boolean isFfw;
	private int oldSpeedIndex;

	private JButton screenShotButton;
	private JComboBox<String> guiUpdateChooser;

	public static final String[] UP_SPEEDS = { "-10", "-1", "0.1", "1", "10",
			"100", "1000", "10000", "100000" };

	public static final double ZOOM_MIN = 0.001;
	public static final double ZOOM_MAX = 10;

	public static final int INITIAL_SPEED_SELECTION = 3;
	public static final int FFW_SPEED_INDEX = 7;

	private double guiUpdateInterval;
	private JSpinner zoomSelector;

	private PlayField pf;
	private DTNSimGUI gui;

	private long lastUpdate;
	private double lastSimTime;
	private double playUntilTime;

	private boolean useHourDisplay = false;

	public GUIControls(DTNSimGUI gui, PlayField pf) {
		this.pf = pf;
		this.gui = gui;
		this.lastUpdate = System.currentTimeMillis();
		this.lastSimTime = 0;
		this.paused = true;
		this.isFfw = false;
		this.playUntilTime = Double.MAX_VALUE;
		initPanel();
	}

	private void initPanel() {
		this.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 4));
		this.setBackground(new Color(245, 248, 252));
		this.setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, new Color(215, 225, 235)),
				new EmptyBorder(2, 6, 2, 6)));

		JLabel timePrefix = new JLabel("Sim Time:");
		timePrefix.setFont(FONT_TEXT);
		this.add(timePrefix);

		this.simTimeField = new JTextField("0.0s");
		this.simTimeField.setFont(FONT_TIME);
		this.simTimeField.setColumns(8);
		this.simTimeField.setEditable(false);
		this.simTimeField.setBackground(Color.WHITE);
		this.simTimeField.setForeground(new Color(24, 43, 73));
		this.simTimeField.setToolTipText(TEXT_SIMTIME);
		this.simTimeField.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON3) {
					useHourDisplay = !useHourDisplay;
				}
				setSimTime(SimClock.getTime());
			}
		});

		this.sepsField = new JLabel("0.00 1/s");
		this.sepsField.setFont(FONT_TEXT);
		this.sepsField.setForeground(new Color(110, 125, 140));
		this.sepsField.setToolTipText(TEXT_SEPS);

		this.screenShotButton = new JButton(TEXT_SCREEN_SHOT);
		this.screenShotButton.setFont(FONT_BTN);

		this.guiUpdateChooser = new JComboBox<String>(UP_SPEEDS);
		this.guiUpdateChooser.setFont(FONT_TEXT);

		this.zoomSelector = new JSpinner(new SpinnerNumberModel(1.0, ZOOM_MIN,
				ZOOM_MAX, 0.001));
		this.zoomSelector.setFont(FONT_TEXT);

		this.add(simTimeField);
		this.add(sepsField);

		this.add(createSeparator());

		playButton = addButton(paused ? ICON_PLAY : ICON_PAUSE,
				paused ? TEXT_PLAY : TEXT_PAUSE);
		stepButton = addButton(ICON_STEP, TEXT_STEP);
		ffwButton = addButton(ICON_FFW, TEXT_FFW);
		playUntilButton = addButton(ICON_PLAY, TEXT_PLAY_UNTIL);
		playUntilButton.setText("...");

		this.add(createSeparator());

		JLabel updateLbl = new JLabel(TEXT_UP_CHOOSER);
		updateLbl.setFont(FONT_TEXT);
		this.add(updateLbl);
		this.add(this.guiUpdateChooser);
		this.guiUpdateChooser.setSelectedIndex(INITIAL_SPEED_SELECTION);
		this.updateUpdateInterval();

		this.add(createSeparator());

		JLabel zoomIconLbl = new JLabel(createImageIcon(ICON_ZOOM));
		this.add(zoomIconLbl);
		this.updateZoomScale(false);
		this.add(this.zoomSelector);

		this.add(createSeparator());
		this.add(this.screenShotButton);

		// Speed multiplier buttons
		this.add(createSeparator());
		JLabel speedLbl = new JLabel("SPEED:");
		speedLbl.setFont(FONT_TEXT);
		speedLbl.setForeground(new Color(100, 115, 130));
		this.add(speedLbl);

		String[] speedLabels = {"0.25x", "0.5x", "1x", "2x", "5x", "10x"};
		// Map speed labels to UP_SPEEDS indices:
		// 0.25x -> index 1 (-1), 0.5x -> index 2 (0.1), 1x -> index 3 (1),
		// 2x -> index 4 (10), 5x -> index 6 (1000), 10x -> index 7 (10000)
		final int[] speedIndices = {1, 2, 3, 4, 6, 7};

		for (int i = 0; i < speedLabels.length; i++) {
			final int speedIdx = speedIndices[i];
			final int btnIdx = i;
			JButton speedBtn = new JButton(speedLabels[i]);
			speedBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
			speedBtn.setMargin(new Insets(2, 4, 2, 4));
			speedBtn.setFocusPainted(false);
			if (speedIdx == INITIAL_SPEED_SELECTION) {
				speedBtn.setBackground(new Color(41, 128, 185));
				speedBtn.setForeground(Color.WHITE);
			} else {
				speedBtn.setBackground(new Color(235, 240, 248));
				speedBtn.setForeground(new Color(24, 43, 73));
			}
			speedBtn.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ev) {
					guiUpdateChooser.setSelectedIndex(speedIdx);
					updateUpdateInterval();
				}
			});
			this.add(speedBtn);
		}

		guiUpdateChooser.addActionListener(this);
		zoomSelector.addChangeListener(this);
		this.screenShotButton.addActionListener(this);
	}

	private JLabel createSeparator() {
		JLabel sep = new JLabel(" | ");
		sep.setForeground(new Color(200, 210, 220));
		return sep;
	}

	private ImageIcon createImageIcon(String path) {
		java.net.URL imgURL = getClass().getResource(PATH_GRAPHICS + path);
		if (imgURL == null) return null;
		return new ImageIcon(imgURL);
	}

	private JButton addButton(String iconPath, String tooltip) {
		ImageIcon icon = createImageIcon(iconPath);
		JButton button = (icon != null) ? new JButton(icon) : new JButton(tooltip);
		button.setToolTipText(tooltip);
		button.setMargin(new Insets(2, 4, 2, 4));
		button.addActionListener(this);
		this.add(button);
		return button;
	}

	public void setSimTime(double time) {
		long timeSinceUpdate = System.currentTimeMillis() - this.lastUpdate;

		if (timeSinceUpdate > EPS_AVG_TIME) {
			double val = ((time - this.lastSimTime) * 1000) / timeSinceUpdate;
			String sepsValue = String.format("%.2f 1/s", val);

			this.sepsField.setText(sepsValue);
			this.lastSimTime = time;
			this.lastUpdate = System.currentTimeMillis();
		}

		if (this.useHourDisplay) {
			int hours = (int) (time / 3600);
			int mins = (int) ((time - hours * 3600) / 60);
			double secs = time % 60;
			this.simTimeField.setText(String.format("%02d:%02d:%02.1f",
					hours, mins, secs));
		} else {
			this.simTimeField.setText(String.format("%.1fs", time));
		}
	}

	public void setPaused(boolean paused) {
		if (!paused) {
			ImageIcon icon = createImageIcon(ICON_PAUSE);
			if (icon != null) this.playButton.setIcon(icon);
			this.playButton.setToolTipText(TEXT_PAUSE);
			this.paused = false;
			if (SimClock.getTime() >= this.playUntilTime) {
				this.playUntilTime = Double.MAX_VALUE;
			}
		} else {
			ImageIcon icon = createImageIcon(ICON_PLAY);
			if (icon != null) this.playButton.setIcon(icon);
			this.playButton.setToolTipText(TEXT_PLAY);
			this.paused = true;
			this.setSimTime(SimClock.getTime());
			this.pf.updateField();
		}
	}

	private void switchFfw() {
		if (isFfw) {
			this.isFfw = false;
			ImageIcon icon = createImageIcon(ICON_FFW);
			if (icon != null) this.ffwButton.setIcon(icon);
			this.guiUpdateChooser.setSelectedIndex(oldSpeedIndex);
			this.ffwButton.setSelected(false);
		} else {
			this.oldSpeedIndex = this.guiUpdateChooser.getSelectedIndex();
			this.guiUpdateChooser.setSelectedIndex(FFW_SPEED_INDEX);
			this.isFfw = true;
			ImageIcon icon = createImageIcon(ICON_PLAY);
			if (icon != null) this.ffwButton.setIcon(icon);
		}
	}

	public boolean isPaused() {
		if (step) {
			step = false;
			return false;
		}
		if (SimClock.getTime() >= this.playUntilTime) {
			this.setPaused(true);
		}
		return this.paused;
	}

	public boolean isFfw() {
		return this.isFfw;
	}

	public double getUpdateInterval() {
		return this.guiUpdateInterval;
	}

	public void changeZoom(int delta) {
		SpinnerNumberModel model = (SpinnerNumberModel) this.zoomSelector.getModel();
		double curZoom = model.getNumber().doubleValue();
		double newValue = curZoom + model.getStepSize().doubleValue() * delta * curZoom * 100;

		if (newValue < ZOOM_MIN) {
			newValue = ZOOM_MIN;
		} else if (newValue > ZOOM_MAX) {
			newValue = ZOOM_MAX;
		}

		model.setValue(newValue);
		this.updateZoomScale(true);
	}

	public void actionPerformed(ActionEvent e) {
		if (e.getSource() == this.playButton) {
			setPaused(!this.paused);
		} else if (e.getSource() == this.stepButton) {
			setPaused(true);
			this.step = true;
		} else if (e.getSource() == this.ffwButton) {
			switchFfw();
		} else if (e.getSource() == this.playUntilButton) {
			setPlayUntil();
		} else if (e.getSource() == this.guiUpdateChooser) {
			updateUpdateInterval();
		} else if (e.getSource() == this.screenShotButton) {
			takeScreenShot();
		}
	}

	public void stateChanged(ChangeEvent e) {
		updateZoomScale(true);
	}

	private void setPlayUntil() {
		setPaused(true);
		String value = JOptionPane.showInputDialog(TEXT_PLAY_UNTIL);
		if (value == null) {
			return;
		}
		try {
			this.playUntilTime = Double.parseDouble(value);
			setPaused(false);
		} catch (NumberFormatException e) {
			JOptionPane.showMessageDialog(gui.getParentFrame(),
					"Invalid number '" + value + "'",
					"error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void updateUpdateInterval() {
		String selString = (String) this.guiUpdateChooser.getSelectedItem();
		this.guiUpdateInterval = Double.parseDouble(selString);
	}

	private void updateZoomScale(boolean centerView) {
		double scale = ((SpinnerNumberModel) zoomSelector.getModel()).getNumber().doubleValue();

		if (centerView) {
			Coord center = gui.getCenterViewCoord();
			this.pf.setScale(scale);
			gui.centerViewAt(center);
		} else {
			this.pf.setScale(scale);
		}
	}

	private void takeScreenShot() {
		try {
			JFileChooser fc = new JFileChooser();
			fc.setSelectedFile(new File(SCREENSHOT_FILE + "." + SCREENSHOT_FILE_TYPE));
			int retVal = fc.showSaveDialog(this);
			if (retVal == JFileChooser.APPROVE_OPTION) {
				File file = fc.getSelectedFile();
				BufferedImage i = new BufferedImage(this.pf.getWidth(),
						this.pf.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D g2 = i.createGraphics();

				this.pf.paint(g2);
				ImageIO.write(i, SCREENSHOT_FILE_TYPE, file);
			}
		} catch (Exception e) {
			JOptionPane.showMessageDialog(gui.getParentFrame(),
					"screenshot failed (problems with output file?)",
					"Exception", JOptionPane.ERROR_MESSAGE);
		}
	}
}
