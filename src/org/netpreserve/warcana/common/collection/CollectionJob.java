package org.netpreserve.warcana.common.collection;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable state for an in-progress immutable WARC.
 */
public class CollectionJob {
    public int version = 1;
    public String key;
    public String platform;
    public String account;
    @JsonAlias("partialWarc")
    public String openWarc;
    public String finalWarc;
    public String mediaOpenWarc;
    public String mediaFinalWarc;
    public long mediaCommittedOffset;
    public String videoOpenWarc;
    public String videoFinalWarc;
    public long videoCommittedOffset;
    public String cursor;
    public String stopIdentity;
    public String newestIdentity;
    public String since;
    public String until;
    public int maxPosts;
    public int maxPages;
    public boolean includeMedia;
    public boolean includeVideo = true;
    public boolean allVideoVariants;
    public long maxMediaBytes;
    public long maxTotalMediaBytes;
    public int pageSize;
    public String userAgent;
    public String service;
    public int postsArchived;
    public int pagesFetched;
    public long committedOffset;
    public List<String> pendingMedia = new ArrayList<>();
    public Set<String> seenMedia = new LinkedHashSet<>();
    public List<String> pendingVideo = new ArrayList<>();
    public Set<String> seenVideo = new LinkedHashSet<>();
    public int mediaCaptured;
    public int mediaFailed;
    public int mediaSkipped;
    public long mediaBytes;
    public int videoCaptured;
    public int videoFailed;
    public int videoSkipped;
    public long videoBytes;
    public String startedAt;

    public CollectionJob() {
    }
}
