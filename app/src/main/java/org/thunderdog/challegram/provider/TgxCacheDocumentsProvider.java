/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.provider;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.thunderdog.challegram.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class TgxCacheDocumentsProvider extends DocumentsProvider {
  private static final String ROOT_ID = "tgx_cache";
  private static final String ROOT_DOCUMENT_ID = "root";
  private static final String DOCUMENT_ID_PREFIX = "p:";
  private static final String TITLE = "Telegram X Cache";
  private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

  private static final String[] DEFAULT_ROOT_PROJECTION = {
    DocumentsContract.Root.COLUMN_ROOT_ID,
    DocumentsContract.Root.COLUMN_FLAGS,
    DocumentsContract.Root.COLUMN_TITLE,
    DocumentsContract.Root.COLUMN_DOCUMENT_ID,
    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    DocumentsContract.Root.COLUMN_ICON
  };

  private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_FLAGS
  };

  private static final Set<String> MEDIA_DIRECTORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
    "animations",
    "documents",
    "music",
    "photos",
    "videos",
    "voice",
    "video_notes",
    "temp"
  )));

  @Override
  public boolean onCreate () {
    return true;
  }

  @Override
  public Cursor queryRoots (String[] projection) {
    MatrixCursor cursor = new MatrixCursor(resolveRootProjection(projection));
    File root = getExternalFilesRoot();
    if (root == null) {
      return cursor;
    }

    int flags = DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD;
    cursor.newRow()
      .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
      .add(DocumentsContract.Root.COLUMN_FLAGS, flags)
      .add(DocumentsContract.Root.COLUMN_TITLE, TITLE)
      .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
      .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, root.getFreeSpace())
      .add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.app_launcher);
    return cursor;
  }

  @Override
  public Cursor queryDocument (String documentId, String[] projection) throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(resolveDocumentProjection(projection));
    includeDocument(cursor, documentId, resolveDocumentId(documentId));
    return cursor;
  }

  @Override
  public Cursor queryChildDocuments (String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(resolveDocumentProjection(projection));
    File parent = resolveDocumentId(parentDocumentId);
    if (!parent.isDirectory()) {
      return cursor;
    }

    File[] children = parent.listFiles();
    if (children == null) {
      return cursor;
    }

    Arrays.sort(children, new Comparator<File>() {
      @Override
      public int compare (File left, File right) {
        if (left.isDirectory() != right.isDirectory()) {
          return left.isDirectory() ? -1 : 1;
        }
        return left.getName().compareToIgnoreCase(right.getName());
      }
    });

    for (File child : children) {
      if (isAllowedFile(child)) {
        includeDocument(cursor, getDocumentId(child), child);
      }
    }
    return cursor;
  }

  @Override
  public ParcelFileDescriptor openDocument (String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
    File file = resolveDocumentId(documentId);
    if (!file.isFile()) {
      throw new FileNotFoundException("Document is not a file: " + documentId);
    }

    int accessMode = ParcelFileDescriptor.MODE_READ_ONLY;
    if (!TextUtils.isEmpty(mode) && (mode.indexOf('w') != -1 || mode.indexOf('+') != -1)) {
      accessMode = ParcelFileDescriptor.MODE_READ_WRITE;
    }
    return ParcelFileDescriptor.open(file, accessMode);
  }

  @Override
  public String getDocumentType (String documentId) throws FileNotFoundException {
    return getMimeType(resolveDocumentId(documentId));
  }

  @Override
  public void deleteDocument (String documentId) throws FileNotFoundException {
    if (ROOT_DOCUMENT_ID.equals(documentId)) {
      throw new FileNotFoundException("Cannot delete root document");
    }

    File file = resolveDocumentId(documentId);
    if (!isDeletableFile(file)) {
      throw new FileNotFoundException("Deleting this document is not allowed: " + documentId);
    }
    if (!deleteRecursive(file)) {
      throw new IllegalStateException("Failed to delete document: " + documentId);
    }
  }

  @Override
  public boolean isChildDocument (String parentDocumentId, String documentId) {
    try {
      File parent = resolveDocumentId(parentDocumentId);
      File child = resolveDocumentId(documentId);
      String parentPath = parent.getCanonicalPath();
      String childPath = child.getCanonicalPath();
      return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
  }

  private void includeDocument (MatrixCursor cursor, String documentId, File file) throws FileNotFoundException {
    if (!isAllowedFile(file)) {
      throw new FileNotFoundException("Document is outside allowed cache paths: " + documentId);
    }

    String mimeType = getMimeType(file);
    int flags = 0;
    if (!ROOT_DOCUMENT_ID.equals(documentId) && isDeletableFile(file)) {
      flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
      if (file.isFile()) {
        flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
      }
    }

    cursor.newRow()
      .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
      .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, ROOT_DOCUMENT_ID.equals(documentId) ? TITLE : file.getName())
      .add(DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null)
      .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
      .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
      .add(DocumentsContract.Document.COLUMN_FLAGS, flags);
  }

  private File resolveDocumentId (String documentId) throws FileNotFoundException {
    File root = getExternalFilesRoot();
    if (root == null) {
      throw new FileNotFoundException("External files directory is unavailable");
    }

    File file;
    if (ROOT_DOCUMENT_ID.equals(documentId)) {
      file = root;
    } else if (!TextUtils.isEmpty(documentId) && documentId.startsWith(DOCUMENT_ID_PREFIX)) {
      String encodedPath = documentId.substring(DOCUMENT_ID_PREFIX.length());
      String relativePath;
      try {
        relativePath = new String(Base64.decode(encodedPath, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        throw new FileNotFoundException("Invalid document id: " + documentId);
      }
      file = new File(root, relativePath);
    } else {
      throw new FileNotFoundException("Unknown document id: " + documentId);
    }

    if (!isAllowedFile(file)) {
      throw new FileNotFoundException("Document is outside allowed cache paths: " + documentId);
    }
    return file;
  }

  private String getDocumentId (File file) throws FileNotFoundException {
    File root = getExternalFilesRoot();
    if (root == null) {
      throw new FileNotFoundException("External files directory is unavailable");
    }

    try {
      String rootPath = root.getCanonicalPath();
      String filePath = file.getCanonicalPath();
      if (filePath.equals(rootPath)) {
        return ROOT_DOCUMENT_ID;
      }
      String relativePath = filePath.substring(rootPath.length() + 1);
      return DOCUMENT_ID_PREFIX + Base64.encodeToString(relativePath.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    } catch (IOException e) {
      throw new FileNotFoundException("Unable to resolve document id");
    }
  }

  private boolean isAllowedFile (File file) {
    File root = getExternalFilesRoot();
    if (root == null || file == null) {
      return false;
    }

    try {
      String rootPath = root.getCanonicalPath();
      String filePath = file.getCanonicalPath();
      if (filePath.equals(rootPath)) {
        return true;
      }
      if (!filePath.startsWith(rootPath + File.separator)) {
        return false;
      }

      String relativePath = filePath.substring(rootPath.length() + 1);
      String[] parts = relativePath.split(File.separator.equals("\\") ? "\\\\" : File.separator);
      if (parts.length == 0 || TextUtils.isEmpty(parts[0])) {
        return false;
      }
      if (MEDIA_DIRECTORIES.contains(parts[0])) {
        return true;
      }
      if (!isAccountDirectory(parts[0])) {
        return false;
      }
      if (parts.length == 1) {
        return true;
      }
      return MEDIA_DIRECTORIES.contains(parts[1]);
    } catch (IOException e) {
      return false;
    }
  }

  private boolean isDeletableFile (File file) {
    File root = getExternalFilesRoot();
    if (root == null || file == null || !isAllowedFile(file)) {
      return false;
    }

    try {
      String rootPath = root.getCanonicalPath();
      String filePath = file.getCanonicalPath();
      if (filePath.equals(rootPath)) {
        return false;
      }

      String relativePath = filePath.substring(rootPath.length() + 1);
      String[] parts = relativePath.split(File.separator.equals("\\") ? "\\\\" : File.separator);
      return !(parts.length == 1 && isAccountDirectory(parts[0]));
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isAccountDirectory (String name) {
    return name != null && name.matches("x_account\\d+");
  }

  private boolean deleteRecursive (File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          if (!deleteRecursive(child)) {
            return false;
          }
        }
      }
    }
    return file.delete();
  }

  private String getMimeType (File file) {
    if (file.isDirectory()) {
      return DocumentsContract.Document.MIME_TYPE_DIR;
    }

    String name = file.getName();
    String extension = MimeTypeMap.getFileExtensionFromUrl(name);
    if (!TextUtils.isEmpty(extension)) {
      String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
      if (!TextUtils.isEmpty(mimeType)) {
        return mimeType;
      }
    }

    String mimeType = URLConnection.guessContentTypeFromName(name);
    return !TextUtils.isEmpty(mimeType) ? mimeType : DEFAULT_MIME_TYPE;
  }

  private File getExternalFilesRoot () {
    Context context = getContext();
    return context != null ? context.getExternalFilesDir(null) : null;
  }

  private static String[] resolveRootProjection (String[] projection) {
    return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
  }

  private static String[] resolveDocumentProjection (String[] projection) {
    return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
  }
}
