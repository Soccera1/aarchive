package dev.aarchive;

public final class ArchiveEntry {
    public final String path;
    public final long size;
    public final int type;
    public final boolean link;

    public ArchiveEntry(String path, long size, int type, boolean link) {
        this.path = path;
        this.size = size;
        this.type = type;
        this.link = link;
    }

    public boolean directory() { return type == 0040000; }
    public boolean extractable() { return !link && (directory() || type == 0100000); }
}
