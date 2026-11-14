/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import gui.playfield.PlayField;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;

import core.Settings;
import core.World;

/**
 * Main window for the ONE Simulator with unified light theme, tabbed right panel
 * (Metrics | Node | Path Hops | Log), playfield, and top speed controls.
 */
@SuppressWarnings("serial")
public class MainWindow extends JFrame {
	public static final String GUI_NS = "GUI";
	public static final String GUI_WIN_NS = GUI_NS + ".window";

	public static final String WIN_WIDTH_S = "width";
	public static final String WIN_HEIGHT_S = "height";

	public static final int WIN_DEFAULT_WIDTH = 1360;
	public static final int WIN_DEFAULT_HEIGHT = 860;

	public static final String WINDOW_TITLE = "ONE Simulator - DTN Visualizer";

	private JScrollPane playFieldScroll;
	private PlayField field;
	private MetricsPanel metricsPanel;
	private PathHopsPanel pathHopsPanel;
	private EventLogPanel eventLogPanel;
	private JTabbedPane rightTabs;

	public MainWindow(String scenName, World world, PlayField field,
			GUIControls guiControls, InfoPanel infoPanel,
			EventLogPanel elp, DTNSimGUI gui) {
		super(WINDOW_TITLE + " [" + scenName + "]");
		this.field = field;
		this.eventLogPanel = elp;

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception ignored) {
		}

		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

		Settings s = new Settings(GUI_WIN_NS);
		NodeChooser chooser = new NodeChooser(world.getHosts(), gui);

		// Create Metrics and PathHops panels
		metricsPanel = new MetricsPanel();
		pathHopsPanel = new PathHopsPanel(gui);

		setLayout(new BorderLayout());
		setJMenuBar(new SimMenuBar(field, chooser));

		// -- Left: PlayField + InfoPanel
		JPanel leftPane = new JPanel();
		leftPane.setLayout(new BoxLayout(leftPane, BoxLayout.Y_AXIS));

		playFieldScroll = new JScrollPane(field);
		playFieldScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		playFieldScroll.getVerticalScrollBar().setUnitIncrement(16);
		playFieldScroll.getHorizontalScrollBar().setUnitIncrement(16);

		leftPane.add(playFieldScroll);
		leftPane.add(infoPanel);

		// -- Right: Tabbed pane (Metrics | Node | Path Hops | Log)
		rightTabs = new JTabbedPane(JTabbedPane.TOP);
		rightTabs.setFont(new Font("SansSerif", Font.BOLD, 11));
		rightTabs.setPreferredSize(new Dimension(460, 0));

		JScrollPane metricsScroll = new JScrollPane(metricsPanel);
		metricsScroll.setBorder(null);
		metricsScroll.getVerticalScrollBar().setUnitIncrement(16);
		rightTabs.addTab("Metrics", metricsScroll);

		JScrollPane nodeScroll = new JScrollPane(chooser);
		nodeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		nodeScroll.getVerticalScrollBar().setUnitIncrement(16);
		rightTabs.addTab("Node", nodeScroll);

		JScrollPane pathScroll = new JScrollPane(pathHopsPanel);
		pathScroll.setBorder(null);
		pathScroll.getVerticalScrollBar().setUnitIncrement(16);
		rightTabs.addTab("Path Hops", pathScroll);

		// Event Log directly in Log tab (with integrated top Selections Bar)
		rightTabs.addTab("Log", elp);

		// -- Top controls (toolbar with speed + sim controls)
		JPanel topBar = new JPanel(new BorderLayout());
		topBar.add(guiControls, BorderLayout.CENTER);

		// -- Main split: left (playfield) + right (tabs)
		JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
				leftPane, rightTabs);
		mainSplit.setOneTouchExpandable(true);
		mainSplit.setResizeWeight(0.64);

		this.getContentPane().add(topBar, BorderLayout.NORTH);
		this.getContentPane().add(mainSplit, BorderLayout.CENTER);

		setPreferredSize(new Dimension(
				s.getInt(WIN_WIDTH_S, WIN_DEFAULT_WIDTH),
				s.getInt(WIN_HEIGHT_S, WIN_DEFAULT_HEIGHT)));

		pack();
		setLocationRelativeTo(null);
	}

	public JScrollPane getPlayFieldScroll() {
		return this.playFieldScroll;
	}

	public PlayField getPlayField() {
		return this.field;
	}

	public MetricsPanel getMetricsPanel() {
		return this.metricsPanel;
	}

	public PathHopsPanel getPathHopsPanel() {
		return this.pathHopsPanel;
	}

	public EventLogPanel getEventLogPanel() {
		return this.eventLogPanel;
	}

	public JTabbedPane getRightTabs() {
		return this.rightTabs;
	}

	/**
	 * Switch to the Path Hops tab.
	 */
	public void showPathHopsTab() {
		rightTabs.setSelectedIndex(2); // Path Hops tab
	}

	/**
	 * Switch to the Log tab.
	 */
	public void showLogTab() {
		rightTabs.setSelectedIndex(3); // Log tab
	}
}
