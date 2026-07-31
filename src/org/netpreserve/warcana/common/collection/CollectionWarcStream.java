package org.netpreserve.warcana.common.collection;

import java.util.function.Function;

/**
 * Selects one of the collection's logical WARC streams.
 */
public enum CollectionWarcStream {
    POSTS("warcana-posts-", job -> job.posts, state -> state.posts),
    MEDIA("warcana-media-", job -> job.media, state -> state.media),
    VIDEO("warcana-video-", job -> job.video, state -> state.video);

    private final String filenamePrefix;
    private final Function<CollectionJob, WarcStreamState> jobSelector;
    private final Function<CollectionState, WarcStreamState> stateSelector;

    CollectionWarcStream(
            String filenamePrefix,
            Function<CollectionJob, WarcStreamState> jobSelector,
            Function<CollectionState, WarcStreamState> stateSelector) {
        this.filenamePrefix = filenamePrefix;
        this.jobSelector = jobSelector;
        this.stateSelector = stateSelector;
    }

    public String filenamePrefix() {
        return filenamePrefix;
    }

    public WarcStreamState from(CollectionJob job) {
        return jobSelector.apply(job);
    }

    public WarcStreamState from(CollectionState state) {
        return stateSelector.apply(state);
    }

    public void copyToJob(CollectionState state, CollectionJob job) {
        from(job).copyFrom(from(state));
    }

    public void copyToState(CollectionJob job, CollectionState state) {
        from(state).copyFrom(from(job));
    }
}
