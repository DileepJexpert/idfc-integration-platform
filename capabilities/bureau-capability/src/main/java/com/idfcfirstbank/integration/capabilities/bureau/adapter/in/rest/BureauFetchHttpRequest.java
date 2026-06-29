package com.idfcfirstbank.integration.capabilities.bureau.adapter.in.rest;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Address;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Applicant;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BusinessDetails;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.GstDetails;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Purpose;

import java.time.LocalDate;
import java.util.List;

/**
 * Wire shape for POST /api/v1/bureau/fetch (canonical API §2.3). The IN adapter
 * maps it to the domain {@link BureauFetchRequest}; the domain never sees the
 * transport and callers never see domain internals.
 */
public record BureauFetchHttpRequest(
        ApplicantDto applicant,
        List<BureauType> bureauTypes,
        Purpose purpose,
        String consentRef) {

    public record ApplicantDto(
            String firstName,
            String middleName,
            String lastName,
            LocalDate dob,
            String pan,
            String aadharRef,
            List<AddressDto> addresses,
            String phone,
            String email,
            GstDto gstDetails,
            BusinessDto businessDetails) {
    }

    public record AddressDto(String line1, String line2, String city, String state,
                             String pincode, String addressType) {
    }

    public record GstDto(String gstin, String legalName, String registrationState) {
    }

    public record BusinessDto(String entityName, String entityType, String udyamRef) {
    }

    BureauFetchRequest toDomain(String correlationId) {
        ApplicantDto a = applicant;
        List<Address> addresses = a.addresses() == null ? List.of()
                : a.addresses().stream()
                .map(ad -> new Address(ad.line1(), ad.line2(), ad.city(), ad.state(), ad.pincode(), ad.addressType()))
                .toList();
        GstDetails gst = a.gstDetails() == null ? null
                : new GstDetails(a.gstDetails().gstin(), a.gstDetails().legalName(), a.gstDetails().registrationState());
        BusinessDetails business = a.businessDetails() == null ? null
                : new BusinessDetails(a.businessDetails().entityName(), a.businessDetails().entityType(),
                a.businessDetails().udyamRef());
        Applicant applicantDomain = new Applicant(a.firstName(), a.middleName(), a.lastName(), a.dob(),
                a.pan(), a.aadharRef(), addresses, a.phone(), a.email(), gst, business);
        return new BureauFetchRequest(applicantDomain, bureauTypes, purpose, consentRef, correlationId);
    }
}
