package com.schwab.urlshortener.url;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single shortened URL record.
 *
 * The primary key doubles as the source of the short code: {@link ShortCodeEncoder}
 * base62-encodes it, so codes are dense and collision-free by construction rather than
 * generated and checked. The id comes from a named sequence (not {@code IDENTITY}), so
 * Hibernate assigns it before the insert statement runs, and {@link #assignShortCode}
 * encodes it in a {@code @PrePersist} callback: the short code is written in the same
 * insert as the rest of the row, rather than a separate update after the fact.
 */
@Entity
@Table(name = "urls")
public class Url {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "urls_id_seq")
    @SequenceGenerator(name = "urls_id_seq", sequenceName = "urls_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(name = "long_url", nullable = false, length = 2048)
    private String longUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Url() {
    }

    public Url(String longUrl) {
        this.longUrl = longUrl;
    }

    @PrePersist
    private void assignShortCode() {
        if (shortCode == null) {
            shortCode = ShortCodeEncoder.encode(id);
        }
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
