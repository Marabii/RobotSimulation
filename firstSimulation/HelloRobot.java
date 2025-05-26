package firstSimulation;

import fr.emse.fayol.maqit.simulator.components.ColorInteractionRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import java.awt.Color;

public class HelloRobot extends ColorInteractionRobot {
    public HelloRobot(String name, int field, int debug, int[] pos, Color co, int rows, int columns) {
        super(name, field, debug, pos, co, rows, columns);
    }

    @Override
    public void move(int nb) {
        for (int i = 0; i < nb; i++) {
            if (freeForward()) {
                moveForward();
            } else {
                turnLeft();
            }
        }
    }

    @Override
    public void handleMessage(Message arg0) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'handleMessage'");
    }
}
