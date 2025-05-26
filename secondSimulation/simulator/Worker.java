package simulator;

import java.awt.Color;

import fr.emse.fayol.maqit.simulator.components.ColorInteractionRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.components.Orientation;

/**
 * A worker is a simple mobile obstacle that moves randomly unless blocked.
 */
public class Worker extends ColorInteractionRobot {

    public Worker(String name, int field, int debug, int[] pos, Color color,
            int rows, int columns, long seed) {
        super(name, field, debug, pos, color, rows, columns, seed);
        orientation = Orientation.up;
    }

    @Override
    public void move(int step) {
        for (int i = 0; i < step; i++) {
            if (freeForward()) {
                moveForward();
            } else {
                randomOrientation();
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        // No special handling for workers
    }
}
