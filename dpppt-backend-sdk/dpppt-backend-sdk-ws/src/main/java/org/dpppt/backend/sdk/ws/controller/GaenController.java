/*
 * Copyright (c) 2020 Ubique Innovation AG <https://www.ubique.ch>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */
package org.dpppt.backend.sdk.ws.controller;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.validation.Valid;

import ch.ubique.openapi.docannotations.Documentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.dpppt.backend.sdk.data.gaen.FakeKeyService;
import org.dpppt.backend.sdk.data.gaen.GAENDataService;
import org.dpppt.backend.sdk.model.gaen.DayBuckets;
import org.dpppt.backend.sdk.model.gaen.GaenKey;
import org.dpppt.backend.sdk.model.gaen.GaenRequest;
import org.dpppt.backend.sdk.model.gaen.GaenSecondDay;
import org.dpppt.backend.sdk.model.gaen.GaenUnit;
import org.dpppt.backend.sdk.ws.insertmanager.InsertManager;
import org.dpppt.backend.sdk.ws.insertmanager.OSType;
import org.dpppt.backend.sdk.ws.insertmanager.insertionfilters.NoBase64Filter.KeyIsNotBase64Exception;
import org.dpppt.backend.sdk.ws.security.ValidateRequest;
import org.dpppt.backend.sdk.ws.security.ValidateRequest.ClaimIsBeforeOnsetException;
import org.dpppt.backend.sdk.ws.security.ValidateRequest.InvalidDateException;
import org.dpppt.backend.sdk.ws.security.ValidateRequest.WrongScopeException;
import org.dpppt.backend.sdk.ws.security.signature.ProtoSignature;
import org.dpppt.backend.sdk.ws.security.signature.ProtoSignature.ProtoSignatureWrapper;
import org.dpppt.backend.sdk.utils.UTCInstant;
import org.dpppt.backend.sdk.ws.util.ValidationUtils;
import org.dpppt.backend.sdk.ws.util.ValidationUtils.BadBatchReleaseTimeException;
import org.dpppt.backend.sdk.ws.util.ValidationUtils.DelayedKeyDateClaimIsWrong;
import org.dpppt.backend.sdk.ws.util.ValidationUtils.DelayedKeyDateIsInvalid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import io.jsonwebtoken.Jwts;

@Controller
@RequestMapping("/v1/gaen")
@Documentation(description = "The GAEN endpoint for the mobile clients")
/**
 * The GaenController defines the API endpoints for the mobile clients to access the GAEN functionality of the
 * red backend.
 * Clients can send new Exposed Keys, or request the existing Exposed Keys.
 */
public class GaenController {
	private static final Logger logger = LoggerFactory.getLogger(GaenController.class);

	// releaseBucketDuration is used to delay the publishing of Exposed Keys by splitting the database up into batches of keys
	// in releaseBucketDuration duration. The current batch is never published, only previous batches are published.
	private final Duration releaseBucketDuration;

	private final Duration requestTime;
	private final ValidateRequest validateRequest;
	private final ValidationUtils validationUtils;
	private final InsertManager insertManager;
	private final GAENDataService dataService;
	private final FakeKeyService fakeKeyService;
	private final Duration exposedListCacheControl;
	private final PrivateKey secondDayKey;
	private final ProtoSignature gaenSigner;

	public GaenController(InsertManager insertManager, GAENDataService dataService, FakeKeyService fakeKeyService, ValidateRequest validateRequest,
						  ProtoSignature gaenSigner, ValidationUtils validationUtils, Duration releaseBucketDuration, Duration requestTime,
						  Duration exposedListCacheControl, PrivateKey secondDayKey) {
		this.insertManager = insertManager;
		this.dataService = dataService;
		this.fakeKeyService = fakeKeyService;
		this.releaseBucketDuration = releaseBucketDuration;
		this.validateRequest = validateRequest;
		this.requestTime = requestTime;
		this.validationUtils = validationUtils;
		this.exposedListCacheControl = exposedListCacheControl;
		this.secondDayKey = secondDayKey;
		this.gaenSigner = gaenSigner;
	}

	@PostMapping(value = "/exposed")
	@Documentation(
			description = "Send exposed keys to server - includes a fix for the fact that GAEN doesn't give access to the current day's exposed key",
			responses = {
					"200=>The exposed keys have been stored in the database",
					"400=>Invalid base64 encoding in GaenRequest",
					"403=>Authentication failed"
			})
	public @ResponseBody Callable<ResponseEntity<String>> addExposed(
	        @Valid @RequestBody
			@Documentation(description = "The GaenRequest contains the SecretKey from the guessed infection date, the infection date itself, and some authentication data to verify the test result")
			        GaenRequest gaenRequest,
			@RequestHeader(value = "User-Agent")
            @Documentation(description = "App Identifier (PackageName/BundleIdentifier) + App-Version + OS (Android/iOS) + OS-Version", example = "ch.ubique.android.starsdk;1.0;iOS;13.3")
                    String userAgent,
			@AuthenticationPrincipal
            @Documentation(description = "JWT token that can be verified by the backend server")
					Object principal)
					throws ClaimIsBeforeOnsetException, WrongScopeException, KeyIsNotBase64Exception, DelayedKeyDateIsInvalid {
		var utcNow = UTCInstant.now();

		this.validateRequest.isValid(principal);

		//Filter out non valid keys and insert them into the database (c.f. InsertManager and configured Filters in the WSBaseConfig)
		insertIntoDatabaseIfJWTIsNotFake(gaenRequest.getGaenKeys(), userAgent, principal, utcNow);

		this.validationUtils.validateDelayedKeyDate(utcNow, UTCInstant.of(gaenRequest.getDelayedKeyDate(), GaenUnit.TenMinutes));

		var responseBuilder = ResponseEntity.ok();
		if (principal instanceof Jwt) {
			var originalJWT = (Jwt) principal;
			var jwtBuilder = Jwts.builder().setId(UUID.randomUUID().toString()).setIssuedAt(Date.from(Instant.now()))
					.setIssuer("dpppt-sdk-backend").setSubject(originalJWT.getSubject())
					.setExpiration(Date
							.from(utcNow.atStartOfDay().plusDays(2).getInstant()))
					.claim("scope", "currentDayExposed").claim("delayedKeyDate", gaenRequest.getDelayedKeyDate());
			if (originalJWT.containsClaim("fake")) {
				jwtBuilder.claim("fake", originalJWT.getClaim("fake"));
			}
			String jwt = jwtBuilder.signWith(secondDayKey).compact();
			responseBuilder.header("Authorization", "Bearer " + jwt);
		}
		Callable<ResponseEntity<String>> cb = () -> {
			normalizeRequestTime(utcNow.getTimestamp());
			return responseBuilder.body("OK");
		};
		return cb;
	}

	@PostMapping(value = "/exposednextday")
    @Documentation(description = "Allows the client to send the last exposed key of the infection to the backend server. The JWT must come from a previous call to /exposed",
	responses = {
    		"200=>The exposed key has been stored in the backend",
			"400=>" +
					"- Ivnalid base64 encoded Temporary Exposure Key" +
					"- TEK-date does not match delayedKeyDAte claim in Jwt",
			"403=>No delayedKeyDate claim in authentication"
	})
	public @ResponseBody Callable<ResponseEntity<String>> addExposedSecond(
			@Valid @RequestBody
            @Documentation(description = "The last exposed key of the user")
                    GaenSecondDay gaenSecondDay,
            @Documentation(description = "App Identifier (PackageName/BundleIdentifier) + App-Version + OS (Android/iOS) + OS-Version", example = "ch.ubique.android.starsdk;1.0;iOS;13.3")
			@RequestHeader(value = "User-Agent")
                    String userAgent,
			@AuthenticationPrincipal
            @Documentation(description = "JWT token that can be verified by the backend server, must have been created by /v1/gaen/exposed and contain the delayedKeyDate")
                    Object principal) throws KeyIsNotBase64Exception, DelayedKeyDateClaimIsWrong {
		var utcNow = UTCInstant.now();

		validationUtils.checkForDelayedKeyDateClaim(principal, gaenSecondDay.getDelayedKey());

		//Filter out non valid keys and insert them into the database (c.f. InsertManager and configured Filters in the WSBaseConfig)
		insertIntoDatabaseIfJWTIsNotFake(gaenSecondDay.getDelayedKey(), userAgent, principal, utcNow);

		return () -> {
			normalizeRequestTime(utcNow.getTimestamp());
			return ResponseEntity.ok().body("OK");
		};

	}

	@GetMapping(value = "/exposed/{keyDate}", produces = "application/zip")
	@Documentation(description = "Request the exposed key from a given date",
	responses = {
			"200=>zipped export.bin and export.sig of all keys in that interval",
			"404=>" +
					"- invalid starting key date, doesn't point to midnight UTC" +
					"- _publishedAfter_ is not at the beginning of a batch release time, currently 2h",
	})
	public @ResponseBody ResponseEntity<byte[]> getExposedKeys(
			@PathVariable
			@Documentation(description = "Requested date for Exposed Keys retrieval, in milliseconds since Unix epoch (1970-01-01). It must indicate the beginning of a TEKRollingPeriod, currently midnight UTC.",
					example = "1593043200000")
					long keyDate,
			@RequestParam(required = false)
			@Documentation(description = "Restrict returned Exposed Keys to dates after this parameter. Given in milliseconds since Unix epoch (1970-01-01).",
					example = "1593043200000")
					Long publishedafter)
			throws BadBatchReleaseTimeException, IOException, InvalidKeyException, SignatureException,
			NoSuchAlgorithmException {
		var utcNow = UTCInstant.now();
		if (!validationUtils.isValidKeyDate(UTCInstant.ofEpochMillis(keyDate))) {
			return ResponseEntity.notFound().build();
		}
		if (publishedafter != null && !validationUtils.isValidBatchReleaseTime(UTCInstant.ofEpochMillis(publishedafter), utcNow)) {
			return ResponseEntity.notFound().build();
		}
		
		long now = utcNow.getTimestamp();
		// calculate exposed until bucket
		long publishedUntil = now - (now % releaseBucketDuration.toMillis());

		var exposedKeys = dataService.getSortedExposedForKeyDate(keyDate, publishedafter, publishedUntil);
		exposedKeys = fakeKeyService.fillUpKeys(exposedKeys, publishedafter, keyDate);
		if (exposedKeys.isEmpty()) {
			return ResponseEntity.noContent().cacheControl(CacheControl.maxAge(exposedListCacheControl))
					.header("X-PUBLISHED-UNTIL", Long.toString(publishedUntil)).build();
		}

		ProtoSignatureWrapper payload = gaenSigner.getPayload(exposedKeys);
		
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(exposedListCacheControl))
				.header("X-PUBLISHED-UNTIL", Long.toString(publishedUntil)).body(payload.getZip());
	}

	@GetMapping(value = "/buckets/{dayDateStr}")
	@Documentation(description = "Request the available release batch times for a given day",
			responses = {
					"200=>zipped export.bin and export.sig of all keys in that interval",
					"404=>invalid starting key date, points outside of the retention range"
			})
	public @ResponseBody ResponseEntity<DayBuckets> getBuckets(
			@PathVariable
			@Documentation(description = "Starting date for exposed key retrieval, as ISO-8601 format",
				example = "2020-06-27")
					String dayDateStr) {
		var atStartOfDay = UTCInstant.parseDate(dayDateStr);
		var end = atStartOfDay.plusDays(1);
		var now = UTCInstant.now();
		if (!validationUtils.isDateInRange(atStartOfDay, now)) {
			return ResponseEntity.notFound().build();
		}
		var relativeUrls = new ArrayList<String>();
		var dayBuckets = new DayBuckets();

		String controllerMapping = this.getClass().getAnnotation(RequestMapping.class).value()[0];
		dayBuckets.setDay(dayDateStr).setRelativeUrls(relativeUrls).setDayTimestamp(atStartOfDay.getTimestamp());

		while (atStartOfDay.getTimestamp() < Math.min(now.getTimestamp(),
				end.getTimestamp())) {
			relativeUrls.add(controllerMapping + "/exposed" + "/" + atStartOfDay.getTimestamp());
			atStartOfDay = atStartOfDay.plus(this.releaseBucketDuration);
		}

		return ResponseEntity.ok(dayBuckets);
	}

	private void normalizeRequestTime(long now) {
		long after = UTCInstant.now().getTimestamp();
		long duration = after - now;
		try {
			Thread.sleep(Math.max(requestTime.minusMillis(duration).toMillis(), 0));
		} catch (Exception ex) {
			logger.error("Couldn't equalize request time: {}", ex.toString());
		}
	}
	private void insertIntoDatabaseIfJWTIsNotFake(GaenKey key, String userAgent, Object principal, UTCInstant utcNow) throws KeyIsNotBase64Exception {
		List<GaenKey> keys = new ArrayList<>();
		keys.add(key);
		insertIntoDatabaseIfJWTIsNotFake(keys, userAgent, principal, utcNow);
	}
	private void insertIntoDatabaseIfJWTIsNotFake(List<GaenKey> keys, String userAgent, Object principal, UTCInstant utcNow) throws KeyIsNotBase64Exception {
		try {
			insertManager.insertIntoDatabase(keys, userAgent, principal, utcNow);
		}
		catch(Throwable ex) {
			if(ex instanceof KeyIsNotBase64Exception) throw (KeyIsNotBase64Exception)ex;
		}
	}

	@ExceptionHandler({DelayedKeyDateClaimIsWrong.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ResponseEntity<String> delayedClaimIsWrong() {
		return ResponseEntity.badRequest().body("DelayedKeyDateClaim is wrong");
	}

	@ExceptionHandler({DelayedKeyDateIsInvalid.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ResponseEntity<String> delayedKeyDateIsInvalid() {
		return ResponseEntity.badRequest().body("DelayedKeyDate must be between yesterday and tomorrow");
	}

	@ExceptionHandler({IllegalArgumentException.class, InvalidDateException.class, JsonProcessingException.class,
			MethodArgumentNotValidException.class, BadBatchReleaseTimeException.class, DateTimeParseException.class, ClaimIsBeforeOnsetException.class, KeyIsNotBase64Exception.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ResponseEntity<Object> invalidArguments() {
		return ResponseEntity.badRequest().build();
	}

	@ExceptionHandler({WrongScopeException.class})
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ResponseEntity<Object> forbidden() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

}