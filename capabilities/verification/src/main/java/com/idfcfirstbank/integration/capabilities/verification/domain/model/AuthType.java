package com.idfcfirstbank.integration.capabilities.verification.domain.model;

/** How the capability authenticates to a downstream. Karza = OAuth Bearer; internal
 *  IMPS = BASIC; NONE for the step-1 echo shell. Real creds come from vault (D#1). */
public enum AuthType { OAUTH_BEARER, BASIC, NONE }
