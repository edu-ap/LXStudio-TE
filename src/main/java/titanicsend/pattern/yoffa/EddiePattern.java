package titanicsend.pattern.yoffa;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import titanicsend.model.TEPanelSection;
import titanicsend.pattern.TEPattern;

import java.io.IOException;

// This is just an example to show how we could map an image onto a set of our panels
// Not intended from production use
@LXCategory("Audio Examples")
public class EddiePattern extends TEPattern {

    private static final String IMG_PATH = "src/main/java/titanicsend/pattern/yoffa/assets/eddie.jpg";

    private final ImagePainter eddiePainter;

    public EddiePattern(LX lx) throws IOException {
        super(lx);
        eddiePainter = new ImagePainter(IMG_PATH, colors);
    }

    @Override
    public void onActive() {
        eddiePainter.paint(model.getPanelsBySection(TEPanelSection.FRONT_LEFT));
        eddiePainter.paint(model.getPanelsBySection(TEPanelSection.FRONT_RIGHT));
    }

    @Override
    public void run(double deltaMs) {
        //do nothing
        //this is a static pattern, so no need to keep redrawing
    }

}
