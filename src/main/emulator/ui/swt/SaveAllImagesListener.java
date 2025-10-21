package emulator.ui.swt;

import emulator.UILocale;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.DirectoryDialog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class SaveAllImagesListener extends SelectionAdapter {
	private final MemoryView mv;

	SaveAllImagesListener(final MemoryView mv) {
		super();
		this.mv = mv;
	}

	public final void widgetSelected(final SelectionEvent selectionEvent) {
		final DirectoryDialog directoryDialog;
		(directoryDialog = new DirectoryDialog(mv.getShell())).setText(UILocale.get("MEMORY_VIEW_SAVE_ALL", "Export all images"));
		directoryDialog.setMessage(UILocale.get("MEMORY_VIEW_CHOOSE_DIRECTORY", "Choose a directory"));
		directoryDialog.setFilterPath(System.getProperty("user.dir"));
		final String open;
                if ((open = directoryDialog.open()) != null) {
                        Path targetDir = Paths.get(open);
                        try {
                                Files.createDirectories(targetDir);
                        } catch (Exception ignored) {
                        }
                        for (int i = 0; i < MemoryView.imagesToShow.size(); ++i) {
                                int spriteId = MemoryView.imagesToShow.get(i).drawable.getDebugId();
                                Path targetFile = targetDir.resolve(spriteId + ".png");
                                try {
                                        MemoryView.imagesToShow.get(i).drawable.getImpl().saveToFile(targetFile.toString());
                                } catch (Exception ignored) {
                                }
                        }
                }
        }
}
