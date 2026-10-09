package blocky_momot;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ui.IStartup;

/**
 * Registers the blocky EPackage from the workspace .ecore when the workbench starts, so the
 * Henshin Text editor can resolve {@code ePackageImport blocky} without requiring the blocky plugin
 * to be activated first.
 *
 * <p>See docs/henshin/README.md and grammar-and-syntax.md.
 */
public final class BlockyPackageRegistration implements IStartup {

    private static final String BLOCKY_ECORE_URI = "platform:/resource/blocky_model/model/blocky.ecore";
    private static final String BLOCKY_NS_URI = "http://www.example.org/blocky";

    @Override
    public void earlyStartup() {
        if (EPackage.Registry.INSTANCE.getEPackage(BLOCKY_NS_URI) != null) {
            return; // already registered (e.g. by blocky plugin)
        }
        ResourceSet rs = new ResourceSetImpl();
        try {
            org.eclipse.emf.ecore.resource.Resource r =
                    rs.getResource(org.eclipse.emf.common.util.URI.createURI(BLOCKY_ECORE_URI), true);
            EcoreUtil.resolveAll(rs);
            if (!r.getContents().isEmpty() && r.getContents().get(0) instanceof EPackage) {
                EPackage pkg = (EPackage) r.getContents().get(0);
                EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
                // Henshin Text editor resolves ePackageImport by package name, not nsURI
                EPackage.Registry.INSTANCE.put(pkg.getName(), pkg);
                if (pkg.getNsPrefix() != null && !pkg.getNsPrefix().equals(pkg.getName())) {
                    EPackage.Registry.INSTANCE.put(pkg.getNsPrefix(), pkg);
                }
            }
        } catch (Exception e) {
            // Workspace may not have blocky_model; blocky plugin will register when used
        }
    }
}

