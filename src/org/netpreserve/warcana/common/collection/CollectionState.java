package org.netpreserve.warcana.common.collection;

import java.util.LinkedHashMap;
import java.util.Map;

public class CollectionState {
    public static final int CURRENT_VERSION = 2;

    public int version = CURRENT_VERSION;
    public Map<String, AccountProgress> accounts = new LinkedHashMap<>();
    public WarcStreamState posts = new WarcStreamState();
    public WarcStreamState media = new WarcStreamState();
    public WarcStreamState video = new WarcStreamState();

    public CollectionState() {
    }

    public static class AccountProgress {
        public String platform;
        public String account;
        public String latestIdentity;
        public String updatedAt;

        public AccountProgress() {
        }
    }
}
