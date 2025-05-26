package simulator;

import fr.emse.fayol.maqit.simulator.components.SituatedComponent;
import fr.emse.fayol.maqit.simulator.components.ColorComponent;
import fr.emse.fayol.maqit.simulator.components.ComponentType;

/**
 * A special zone in the warehouse to recharge robots.
 */
public class ColorChargingZone extends SituatedComponent implements ColorComponent {

    private int[] rgb;

    public ColorChargingZone(int[] location, int[] rgb) {
        super(location);
        this.rgb = rgb;
    }

    @Override
    public int[] getColor() {
        return rgb;
    }

    @Override
    public ComponentType getComponentType() {
        return ComponentType.empty;
    }

    @Override
    public String toString() {
        return "ChargingZone";
    }

    @Override
    public void setColor(int[] rgb) {
        this.rgb = rgb;
    }
}
