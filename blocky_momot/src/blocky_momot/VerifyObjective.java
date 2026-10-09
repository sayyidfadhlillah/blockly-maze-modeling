
package blocky_momot;

import blocky.BlockyPackage;
import blocky.Game;
import blocky.Level;
import java.io.File;
import java.util.Collections;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

public class VerifyObjective {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: VerifyObjective <path_to_xmi>");
            return;
        }
        String xmiPath = args[0];

        // Setup EMF
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        BlockyPackage.eINSTANCE.eClass();

        ResourceSet rs = new ResourceSetImpl();
        File f = new File(xmiPath);
        URI uri = URI.createFileURI(f.getAbsolutePath());

        try {
            Resource r = rs.getResource(uri, true);
            Game game = (Game) r.getContents().get(0);
            Level level = game.getLevels().get(0);

            System.out.println("Simulating level: " + level.getTitle());

            // Re-annotate just in case
            BlockySimulator.annotateCells(level);

            int closest = BlockySimulator.closestToGoalOrPenalty(level, 999);
            System.out.println("RESULT_CLOSEST_TO_GOAL: " + closest);

            // Detailed Trace
            System.out.println("\n--- DETAILED TRACE ---");
            blocky.GridMap map = level.getMap();
            blocky.Cell startCell = null;
            for (blocky.Cell c : map.getCells()) {
                if (c.getType() == blocky.CellType.START) {
                    startCell = c;
                    break;
                }
            }
            if (startCell != null) {
                blocky.Direction startDir = BlockySimulator.determineStartOrientation(level, startCell);
                System.out.println(
                        "Start Position: (" + startCell.getX() + ", " + startCell.getY() + ") Type: " + startCell.getType()
                                + " Distance: " + startCell.getDistanceToGoal());
                System.out.println("Start Orientation: " + startDir);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

