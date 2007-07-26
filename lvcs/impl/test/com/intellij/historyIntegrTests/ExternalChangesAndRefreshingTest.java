package com.intellij.historyIntegrTests;


import com.intellij.history.core.Paths;
import com.intellij.history.core.revisions.Revision;
import com.intellij.history.utils.RunnableAdapter;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.*;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Semaphore;

public class ExternalChangesAndRefreshingTest extends IntegrationTestCase {
  public void testRefreshingSynchronously() throws Exception {
    doTestRefreshing(false);
  }

  public void testRefreshingAsynchronously() throws Exception {
    doTestRefreshing(true);
  }

  @Override
  protected void runBareRunnable(Runnable r) throws Throwable {
    if (getName().equals("testRefreshingAsynchronously")) {
      // this methods waits for another thread to finish, that leds
      // to deadlock in swing-thread. Therefore we have to run this test
      // outside of swing-thread
      r.run();
    }
    else {
      super.runBareRunnable(r);
    }
  }

  private void doTestRefreshing(boolean async) throws Exception {
    String path1 = createFileExternally("f1.java");
    String path2 = createFileExternally("f2.java");

    assertFalse(hasVcsEntry(path1));
    assertFalse(hasVcsEntry(path2));

    final Semaphore s = new Semaphore(1);
    s.acquire();
    refreshVFS(async, new Runnable() {
      public void run() {
        s.release();
      }
    });
    s.acquire();

    assertTrue(hasVcsEntry(path1));
    assertTrue(hasVcsEntry(path2));

    assertEquals(2, getVcsRevisionsFor(root).size());
  }

  public void testChangeSetName() throws Exception {
    String path = createFileExternally("f.java");
    refreshVFS();
    Revision r = getVcsRevisionsFor(root).get(0);
    assertEquals("External change", r.getCauseChangeName());
  }

  public void testRefreshDuringCommand() {
    // shouldn't throw
    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        refreshVFS();
      }
    }, "", null);
  }

  public void testCommandDuringRefresh() throws Exception {
    createFileExternally("f.java");

    VirtualFileListener l = new VirtualFileAdapter() {
      @Override
      public void fileCreated(VirtualFileEvent e) {
        executeSomeCommand();
      }
    };

    // shouldn't throw
    addFileListenerDuring(l, new Runnable() {
      public void run() {
        refreshVFS();
      }
    });
  }

  private void executeSomeCommand() {
    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
      }
    }, "", null);
  }

  public void testContentOfFileChangedDuringRefresh() throws Exception {
    final VirtualFile f = root.createChildData(null, "file.java");
    f.setBinaryContent("before".getBytes());

    performAllPendingJobs();

    ContentChangesListener l = new ContentChangesListener(f);
    addFileListenerDuring(l, new RunnableAdapter() {
      @Override
      public void doRun() throws IOException {
        changeFileExternally(f.getPath(), "after");
        refreshVFS();
      }
    });

    // todo unrelable test because content recorded before LvcsFileListener does its job
    assertEquals("before", l.getContentBefore());
    assertEquals("after", l.getContentAfter());
  }

  private void performAllPendingJobs() {
    refreshVFS();
  }

  public void testFileCreationDuringRefresh() throws Exception {
    final String path = createFileExternally("f.java");
    changeFileExternally(path, "content");

    final String[] content = new String[1];
    VirtualFileListener l = new VirtualFileAdapter() {
      @Override
      public void fileCreated(VirtualFileEvent e) {
        try {
          if (!e.getFile().getPath().equals(path)) return;
          content[0] = new String(e.getFile().contentsToByteArray());
        }
        catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      }
    };

    addFileListenerDuring(l, new Runnable() {
      public void run() {
        refreshVFS();
      }
    });
    assertEquals("content", content[0]);
  }

  public void ignoreTestCreationOfExcludedDirectoryDuringRefresh() throws Exception {
    // todo does not work due to FileListener order. FileIndex gets event later than Lvcs.

    VirtualFile dir = root.createChildDirectory(null, "EXCLUDED");
    String p = dir.getPath();

    assertTrue(hasVcsEntry(p));

    ModifiableRootModel m = ModuleRootManager.getInstance(myModule).getModifiableModel();
    m.getContentEntries()[0].addExcludeFolder(dir);
    m.commit();

    assertFalse(hasVcsEntry(p));

    dir.delete(null);

    createDirectoryExternally("EXCLUDED");
    refreshVFS();

    assertFalse(hasVcsEntry(p));
  }

  public void testDeletionOfFilteredDirectoryExternallyDoesNotThrowExceptionDuringRefresh() throws Exception {
    VirtualFile f = root.createChildDirectory(null, EXCLUDED_DIR_NAME);
    String path = Paths.appended(root.getPath(), EXCLUDED_DIR_NAME);

    assertFalse(hasVcsEntry(path));

    new File(path).delete();
    refreshVFS();

    assertFalse(hasVcsEntry(path));
  }

  private void refreshVFS() {
    refreshVFS(false, null);
  }

  private void refreshVFS(boolean async, Runnable after) {
    VirtualFileManager.getInstance().refresh(async, after);
  }
}