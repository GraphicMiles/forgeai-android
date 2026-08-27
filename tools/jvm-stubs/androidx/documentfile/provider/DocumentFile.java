package androidx.documentfile.provider;
import android.content.Context; import android.net.Uri;
public abstract class DocumentFile {
  public static DocumentFile fromTreeUri(Context c, Uri u){return null;}
  public static DocumentFile fromSingleUri(Context c, Uri u){return null;}
  public abstract Uri getUri();
  public abstract String getName();
  public abstract boolean isDirectory();
  public abstract boolean isFile();
  public abstract long length();
  public abstract long lastModified();
  public abstract boolean exists();
  public abstract boolean canRead();
  public abstract boolean canWrite();
  public abstract boolean delete();
  public abstract DocumentFile[] listFiles();
  public abstract DocumentFile findFile(String name);
  public abstract DocumentFile createFile(String mime, String name);
  public abstract DocumentFile createDirectory(String name);
  public abstract boolean renameTo(String name);
}
