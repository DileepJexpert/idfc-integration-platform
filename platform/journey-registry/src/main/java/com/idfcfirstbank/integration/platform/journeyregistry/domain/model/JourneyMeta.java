package com.idfcfirstbank.integration.platform.journeyregistry.domain.model;

/**
 * One journey's coordination record: identity + scoping and the three version
 * pointers the store keeps ATOMIC (compare-and-set on the record generation):
 *
 * <ul>
 *   <li>{@code latestVersion} — highest allocated version number;</li>
 *   <li>{@code editableVersion} — the single in-pipeline (draft/pending) version,
 *       0 = none. Allocating a draft CASes 0 -> v: at most ONE editable draft
 *       exists per journey, by construction, under concurrency;</li>
 *   <li>{@code publishedVersion} — the version serving NEW runs, 0 = none.</li>
 * </ul>
 */
public record JourneyMeta(
        String key,
        String name,
        String businessLine,
        String product,
        String partner,
        int latestVersion,
        int editableVersion,
        int publishedVersion) {

    public static JourneyMeta created(String key, String name, String businessLine,
                                      String product, String partner) {
        return new JourneyMeta(key, name, businessLine, product, partner, 0, 0, 0);
    }

    public boolean hasEditable() {
        return editableVersion != 0;
    }

    public boolean hasPublished() {
        return publishedVersion != 0;
    }
}
