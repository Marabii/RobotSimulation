package firstSimulation;

import java.util.List;

import fr.emse.fayol.maqit.simulator.SimFactory;
import fr.emse.fayol.maqit.simulator.components.ColorObstacle;
import fr.emse.fayol.maqit.simulator.components.Robot;
import fr.emse.fayol.maqit.simulator.configuration.IniFile;
import fr.emse.fayol.maqit.simulator.configuration.SimProperties;
import fr.emse.fayol.maqit.simulator.environment.Cell;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;

public class HelloSimulation extends SimFactory {

    public static void main(String[] args) throws Exception {
        IniFile ifile = new IniFile("parameters/configuration.ini");
        SimProperties sp = new SimProperties(ifile);
        sp.simulationParams();
        sp.displayParams();

        HelloSimulation hs = new HelloSimulation(sp);
        hs.createEnvironment();
        hs.createObstacle();
        hs.createRobot();
        hs.initializeGW();
        hs.schedule();
    }

    public HelloSimulation(SimProperties sp) {
        super(sp);
    }

    @Override
    public void createEnvironment() {
        environment = new ColorGridEnvironment(sp.rows, sp.columns, sp.debug, sp.seed);
    }

    @Override
    public void createGoal() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'createGoal'");
    }

    @Override
    public void createObstacle() {
        if (environment == null) {
            throw new IllegalStateException("Environment must be created before adding obstacles!");
        }

        for (int i = 0; i < sp.nbobstacle; i++) {
            int[] freePos = environment.getPlace();
            ColorObstacle co = new ColorObstacle(freePos, sp.colorobstacle);
            addNewComponent(co);
        }
    }

    @Override
    public void createRobot() {
        if (environment == null) {
            throw new IllegalStateException("Environment must be created before adding robots!");
        }

        for (int i = 0; i < sp.nbrobot; i++) {
            int[] freePos = environment.getPlace();

            HelloRobot robot = new HelloRobot(
                    "robot " + (i + 1),
                    sp.field,
                    sp.debug,
                    freePos,
                    sp.colorrobot,
                    sp.rows,
                    sp.columns);

            addNewComponent(robot);
        }
    }

    @Override
    public void schedule() {
        List<Robot> lr = environment.getRobot();
        for (int i = 0; i < sp.step; i++) {
            for (Robot t : lr) {
                int[] posr = t.getLocation();
                Cell[][] p = environment.getNeighbor(t.getX(),
                        t.getY(), t.getField());
                t.updatePerception(p);
                t.move(1);
                updateEnvironment(posr, t.getLocation());
            }
            refreshGW();
            try {
                Thread.sleep(sp.waittime);
            } catch (InterruptedException ie) {
                System.out.println(ie);
            }
        }
    }

}