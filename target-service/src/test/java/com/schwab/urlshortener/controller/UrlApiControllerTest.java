package com.schwab.urlshortener.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schwab.urlshortener.domain.Url;
import com.schwab.urlshortener.exception.GlobalExceptionHandler;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.filter.CorrelationIdFilter;
import com.schwab.urlshortener.service.UrlService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("fast")
@WebMvcTest({UrlApiController.class, RedirectController.class, GlobalExceptionHandler.class, CorrelationIdFilter.class})
@TestPropertySource(properties = "app.base-url=http://short.test")
class UrlApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlService urlService;

    @MockBean
    private Clock clock;

    @Test
    void createReturns201WithShortUrl() throws Exception {
        Url url = new Url("https://example.com/page");
        url.setShortCode("1");
        url.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(urlService.create(eq("https://example.com/page"), any())).thenReturn(url);

        mockMvc.perform(post("/api/urls")
                .contentType("application/json")
                .content("{\"longUrl\":\"https://example.com/page\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shortCode").value("1"))
            .andExpect(jsonPath("$.shortUrl").value("http://short.test/1"))
            .andExpect(jsonPath("$.longUrl").value("https://example.com/page"));
    }

    @Test
    void createRejectsBlankLongUrl() throws Exception {
        mockMvc.perform(post("/api/urls")
                .contentType("application/json")
                .content("{\"longUrl\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsNonHttpUrl() throws Exception {
        mockMvc.perform(post("/api/urls")
                .contentType("application/json")
                .content("{\"longUrl\":\"ftp://example.com\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("longUrl")));
    }

    @Test
    void lookupReturns404ForUnknownCode() throws Exception {
        when(urlService.findByShortCode(anyString())).thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/api/urls/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void redirectReturns302ToLongUrl() throws Exception {
        Url url = new Url("https://example.com/target");
        url.setShortCode("abc");
        url.setCreatedAt(Instant.now());
        when(urlService.resolveForRedirect("abc")).thenReturn(url);

        mockMvc.perform(get("/abc"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://example.com/target"));
    }

    @Test
    void redirectReturns404ForUnknownCode() throws Exception {
        when(urlService.resolveForRedirect("missing")).thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void redirectReturns410ForExpiredCode() throws Exception {
        when(urlService.resolveForRedirect("expired")).thenThrow(new UrlExpiredException("expired"));

        mockMvc.perform(get("/expired"))
            .andExpect(status().isGone());
    }

    @Test
    void correlationIdFromInboundHeaderIsPresentOnTheErrorResponse() throws Exception {
        when(urlService.findByShortCode(anyString())).thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/api/urls/missing").header(CorrelationIdFilter.HEADER_NAME, "test-correlation-id"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "test-correlation-id"))
            .andExpect(jsonPath("$.correlationId").value("test-correlation-id"));
    }

    @Test
    void correlationIdIsGeneratedWhenNotProvidedOnTheRequest() throws Exception {
        when(urlService.findByShortCode(anyString())).thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/api/urls/missing"))
            .andExpect(status().isNotFound())
            .andExpect(header().exists(CorrelationIdFilter.HEADER_NAME));
    }
}
