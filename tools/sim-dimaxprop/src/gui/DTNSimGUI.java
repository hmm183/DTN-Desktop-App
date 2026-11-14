/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import gui.playfield.PlayField;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import movement.Path;
import ui.DTNSimUI;
import core.Coord;
import core.DTNHost;
import core.Message;
import core.SimClock;

/**
 * Graphical User Interface for simulator.
 * Wires up MetricsPanel, PathHopsPanel, and post-simulation path highlighting.
 */
public class DTNSimGUI extends DTNSimUI {
	private MainWindow main;
	private PlayField field;
	private GUIControls guiControls;
	private EventLogPanel eventLogPanel;
	private InfoPanel infoPanel;

	private void startGUI() {
		try {
			SwingUtilities.invokeAndWait(new Runnable() {
			    public void run() {
					try {
						initGUI();
					} catch (AssertionError e) {
						processAssertionError(e);
					}
			    }
			});
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.exit(-1);
		} catch (InvocationTargetException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	/**
	 * Initializes the GUI
	 */
	private void initGUI() {
		this.field = new PlayField(world, this);

		this.field.addMouseListener(new PlayfieldMouseHandler());
		this.field.addMouseWheelListener(new PlayfieldMouseHandler());

		this.guiControls = new GUIControls(this, this.field);
		this.eventLogPanel = new EventLogPanel(this);
		this.infoPanel = new InfoPanel(this);
		this.main = new MainWindow(this.scen.getName(), world, field,
				guiControls, infoPanel, eventLogPanel, this);

		// Register event listeners
		scen.addMessageListener(eventLogPanel);
		scen.addConnectionListener(eventLogPanel);

		// Register MetricsPanel as listener
		scen.addMessageListener(main.getMetricsPanel());
		scen.addConnectionListener(main.getMetricsPanel());

		// Register PathHopsPanel as listener
		scen.addMessageListener(main.getPathHopsPanel());

		if (scen.getMap() != null) {
			field.setMap(scen.getMap());
		}

		// if user closes the main window, call closeSim()
		this.main.addWindowListener(new WindowAdapter() {
			private boolean closeAgain = false;
			public void windowClosing(WindowEvent e) {
				closeSim();
				if (closeAgain) {
					System.err.println("Forced close. " +
							"Some reports may have not been finalized.");
					System.exit(-1);
				}
				closeAgain = true;
			}
		});

		this.main.setVisible(true);
	}

	@Override
	protected void runSim() {
		double simTime = SimClock.getTime();
		double endTime = scen.getEndTime();

		startGUI();

		while (simTime < endTime && !simCancelled) {
			if (guiControls.isPaused()) {
				wait(10);
			}
			else {
				try {
					world.update();
				} catch (AssertionError e) {
					processAssertionError(e);
				}
				simTime = SimClock.getTime();
			}
			this.update(false);
		}

		simDone = true;
		done();
		this.update(true);

		if (!simCancelled) {
			// Post-simulation: enable message path highlighting
			enablePostSimulationPathViewer();

			JOptionPane.showMessageDialog(getParentFrame(),
					"Simulation complete!\n\n" +
					"You can now select any delivered message in the\n" +
					"'Path Hops' tab to highlight its route on the map.",
					"Simulation Done", JOptionPane.INFORMATION_MESSAGE);
		}
		else {
			System.exit(0);
		}
	}

	/**
	 * After simulation completes, populate the PathHopsPanel with all
	 * delivered messages so the user can select and highlight paths.
	 */
	private void enablePostSimulationPathViewer() {
		final List<Message> deliveredMessages = new ArrayList<Message>();

		// Collect all messages currently held by nodes that have reached their destination
		for (DTNHost host : world.getHosts()) {
			for (Message m : host.getMessageCollection()) {
				if (m.getTo() == host && m.getHopCount() > 0) {
					deliveredMessages.add(m);
				}
			}
		}

		// Also collect messages from all hosts that have hop path records
		// (even if already removed from buffer, the PathHopsPanel tracked them)

		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				main.getPathHopsPanel().showPostSimulationSelector(deliveredMessages);
				main.showPathHopsTab();
			}
		});
	}

	private void processAssertionError(AssertionError e) {
		String title = e.getClass().getSimpleName() + " (simulation paused)";
		String msg = e.getMessage();
		String txt = (msg != null ? msg : "") + " at simtime " +
			SimClock.getIntTime() +	"\n\ncaught at:\n" +
			e.getStackTrace()[0].toString() +
			"\nNote that the simulation might be in inconsistent state, "+
			"continue only with caution.\n\n Show rest of the stack trace?";

		if (guiControls != null) {
			guiControls.setPaused(true);
		}

		int selection = JOptionPane.showOptionDialog(getParentFrame(), txt,
				title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
				null, null, null);

		if (selection == 0) {
			txt = "";
			for (StackTraceElement trace : e.getStackTrace()) {
				txt += trace.toString() + "\n";
			}
			JOptionPane.showMessageDialog(getParentFrame(), txt,
				"stack trace", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	public void closeSim() {
		if (simDone) {
			System.exit(0);
		}
		this.world.cancelSim();
		this.simCancelled = true;
	}

    public void update(boolean forcedUpdate) {
		double guiUpdateInterval = guiControls.getUpdateInterval();

		if (!forcedUpdate && guiUpdateInterval > (SimClock.getTime()
				- this.lastUpdate)) {
			return;
		}

		try {
			SwingUtilities.invokeAndWait(new Runnable() {
			    public void run() {
					updateView();
			    }
			});
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}

		if (guiUpdateInterval < 0) {
			wait(100 * (int)(-guiUpdateInterval));
		}
    }

    private void updateView() {
		double simTime = SimClock.getTime();
		this.lastUpdate = simTime;
		guiControls.setSimTime(simTime);
		this.field.updateField();
    }

    public void setPaused(boolean paused) {
		this.guiControls.setPaused(paused);
    }

    public void setFocus(DTNHost host) {
		centerViewAt(host.getLocation());
		infoPanel.showInfo(host);
		showPath(host.getPath());
    }

    public void showPath(Path path) {
		field.addPath(path);
    }

    public Coord getCenterViewCoord() {
		JScrollPane sp = main.getPlayFieldScroll();
		double midX, midY;

		midX = sp.getHorizontalScrollBar().getValue() +
			sp.getViewport().getWidth() / 2;
		midY = sp.getVerticalScrollBar().getValue() +
			sp.getViewport().getHeight() / 2;

		return this.field.getWorldPosition(new Coord(midX, midY));
    }

    public void centerViewAt(Coord loc) {
		JScrollPane sp = main.getPlayFieldScroll();
		Coord gLoc = this.field.getGraphicsPosition(loc);
		int midX, midY;

		updateView();

		midX = (int) gLoc.getX() - sp.getViewport().getWidth() / 2;
		midY = (int) gLoc.getY() - sp.getViewport().getHeight() / 2;

		sp.getHorizontalScrollBar().setValue(midX);
		sp.getVerticalScrollBar().setValue(midY);
    }

    public InfoPanel getInfoPanel() {
		return this.infoPanel;
    }

    public MainWindow getParentFrame() {
		return this.main;
    }

	private void wait(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			// nothing to do here
		}
	}

	/**
	 * Handler for playfield's mouse clicks.
	 */
	private class PlayfieldMouseHandler extends MouseAdapter implements
		MouseWheelListener {
		public void mouseClicked(MouseEvent e) {
			java.awt.Point p = e.getPoint();
			centerViewAt(field.getWorldPosition(new Coord(p.x, p.y)));
		}

		public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
			guiControls.changeZoom(e.getWheelRotation());
		}
	}
}
