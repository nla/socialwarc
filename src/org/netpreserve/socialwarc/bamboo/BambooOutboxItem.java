package org.netpreserve.socialwarc.bamboo;

import java.util.ArrayList;
import java.util.List;

public class BambooOutboxItem {
    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String id;
    public long seriesId;
    public String name;
    public String description;
    public String createdAt;
    public Long crawlId;
    public String status = "pending";
    public int attempts;
    public String nextAttemptAt;
    public String lastError;
    public String completedAt;
    public List<File> files = new ArrayList<>();

    public static class File {
        public String path;
        public String filename;
        public long size;
        public String sha256;
        public boolean uploaded;
        public Long bambooWarcId;

        public File() {
        }
    }

    public BambooOutboxItem() {
    }
}
