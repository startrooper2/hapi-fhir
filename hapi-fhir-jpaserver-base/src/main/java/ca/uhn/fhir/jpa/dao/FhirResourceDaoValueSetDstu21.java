package ca.uhn.fhir.jpa.dao;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2015 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.binary.StringUtils;
import org.hl7.fhir.dstu21.model.CodeableConcept;
import org.hl7.fhir.dstu21.model.Coding;
import org.hl7.fhir.dstu21.model.IdType;
import org.hl7.fhir.dstu21.model.ValueSet;
import org.hl7.fhir.dstu21.model.ValueSet.ConceptDefinitionComponent;
import org.hl7.fhir.dstu21.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.dstu21.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.dstu21.model.ValueSet.ValueSetExpansionContainsComponent;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.entity.BaseHasResource;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.validation.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.ValidationSupportChain;

public class FhirResourceDaoValueSetDstu21 extends FhirResourceDaoDstu21<ValueSet>implements IFhirResourceDaoValueSet<ValueSet, Coding, CodeableConcept> {

	@Autowired
	private IJpaValidationSupportDstu21 myJpaValidationSupport;
	
	private ValidationSupportChain myValidationSupport;
	
	@Autowired
	@Qualifier("myFhirContextDstu2Hl7Org")
	private FhirContext myRiCtx;

	private DefaultProfileValidationSupport myDefaultProfileValidationSupport;

	@Override
	@PostConstruct
	public void postConstruct() {
		super.postConstruct();
//		myDefaultProfileValidationSupport = new DefaultProfileValidationSupport();
//		myValidationSupport = new ValidationSupportChain(myDefaultProfileValidationSupport, myJpaValidationSupport);
	}
	
	@Override
	public ValueSet expand(IIdType theId, String theFilter) {
		ValueSet source = loadValueSetForExpansion(theId);
		return expand(source, theFilter);

	}

	private ValueSet loadValueSetForExpansion(IIdType theId) {
		if (theId.getValue().startsWith("http://hl7.org/fhir/")) {
			org.hl7.fhir.instance.model.ValueSet valueSet = myValidationSupport.fetchResource(myRiCtx, org.hl7.fhir.instance.model.ValueSet.class, theId.getValue());
			if (valueSet != null) {
				return getContext().newJsonParser().parseResource(ValueSet.class, myRiCtx.newJsonParser().encodeResourceToString(valueSet));
			}
		}
		BaseHasResource sourceEntity = readEntity(theId);
		if (sourceEntity == null) {
			throw new ResourceNotFoundException(theId);
		}
		ValueSet source = (ValueSet) toResource(sourceEntity, false);
		return source;
	}

	@Override
	public ValueSet expandByIdentifier(String theUri, String theFilter) {
		if (isBlank(theUri)) {
			throw new InvalidRequestException("URI must not be blank or missing");
		}
		ValueSet source;
		
		org.hl7.fhir.instance.model.ValueSet defaultValueSet = myDefaultProfileValidationSupport.fetchResource(myRiCtx, org.hl7.fhir.instance.model.ValueSet.class, theUri);
		if (defaultValueSet != null) {
			source = getContext().newJsonParser().parseResource(ValueSet.class, myRiCtx.newJsonParser().encodeResourceToString(defaultValueSet));
		} else {
			IBundleProvider ids = search(ValueSet.SP_URL, new UriParam(theUri));
			if (ids.size() == 0) {
				throw new InvalidRequestException("Unknown ValueSet URI: " + theUri);
			}
			source = (ValueSet) ids.getResources(0, 1).get(0);
		}

		return expand(source, theFilter);

	}

	@Override
	public ValueSet expand(ValueSet source, String theFilter) {
		ValueSet retVal = new ValueSet();
		retVal.setDate(new Date());
		
		/*
		 * Add composed concepts
		 */

		for (ConceptSetComponent nextInclude : source.getCompose().getInclude()) {
			for (ConceptReferenceComponent next : nextInclude.getConcept()) {
				if (isBlank(theFilter)) {
					addCompose(retVal, nextInclude.getSystem(), next.getCode(), next.getDisplay());
				} else {
					String filter = theFilter.toLowerCase();
					if (next.getDisplay().toLowerCase().contains(filter) || next.getCode().toLowerCase().contains(filter)) {
						addCompose(retVal, nextInclude.getSystem(), next.getCode(), next.getDisplay());
					}
				}
			}
		}

		/*
		 * Add defined concepts
		 */

		for (ConceptDefinitionComponent next : source.getCodeSystem().getConcept()) {
			addCompose(theFilter, retVal, source, next);
		}

		return retVal;
	}

	private void addCompose(String theFilter, ValueSet theValueSetToPopulate, ValueSet theSourceValueSet, ConceptDefinitionComponent theConcept) {
		if (isBlank(theFilter)) {
			addCompose(theValueSetToPopulate, theSourceValueSet.getCodeSystem().getSystem(), theConcept.getCode(), theConcept.getDisplay());
		} else {
			String filter = theFilter.toLowerCase();
			if (theConcept.getDisplay().toLowerCase().contains(filter) || theConcept.getCode().toLowerCase().contains(filter)) {
				addCompose(theValueSetToPopulate, theSourceValueSet.getCodeSystem().getSystem(), theConcept.getCode(), theConcept.getDisplay());
			}
		}
		for (ConceptDefinitionComponent nextChild : theConcept.getConcept()) {
			addCompose(theFilter, theValueSetToPopulate, theSourceValueSet, nextChild);
		}
	}

	private void addCompose(ValueSet retVal, String theSystem, String theCode, String theDisplay) {
		if (isBlank(theCode)) {
			return;
		}
		ValueSetExpansionContainsComponent contains = retVal.getExpansion().addContains();
		contains.setSystem(theSystem);
		contains.setCode(theCode);
		contains.setDisplay(theDisplay);
	}

	@Override
	public ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.ValidateCodeResult validateCode(IPrimitiveType<String> theValueSetIdentifier, IIdType theId, IPrimitiveType<String> theCode, IPrimitiveType<String> theSystem, IPrimitiveType<String> theDisplay, Coding theCoding, CodeableConcept theCodeableConcept) {
		List<IIdType> valueSetIds;

		boolean haveCodeableConcept = theCodeableConcept != null && theCodeableConcept.getCoding().size() > 0;
		boolean haveCoding = theCoding != null && theCoding.isEmpty() == false;
		boolean haveCode = theCode != null && theCode.isEmpty() == false;

		if (!haveCodeableConcept && !haveCoding && !haveCode) {
			throw new InvalidRequestException("No code, coding, or codeableConcept provided to validate");
		}
		if (!multiXor(haveCodeableConcept, haveCoding, haveCode)) {
			throw new InvalidRequestException("$validate-code can only validate (system AND code) OR (coding) OR (codeableConcept)");
		}

		boolean haveIdentifierParam = theValueSetIdentifier != null && theValueSetIdentifier.isEmpty() == false;
		if (theId != null) {
			valueSetIds = Collections.singletonList(theId);
		} else if (haveIdentifierParam) {
			Set<Long> ids = searchForIds(ValueSet.SP_IDENTIFIER, new TokenParam(null, theValueSetIdentifier.getValue()));
			valueSetIds = new ArrayList<IIdType>();
			for (Long next : ids) {
				valueSetIds.add(new IdType("ValueSet", next));
			}
		} else {
			if (theCode == null || theCode.isEmpty()) {
				throw new InvalidRequestException("Either ValueSet ID or ValueSet identifier or system and code must be provided. Unable to validate.");
			}
			String code = theCode.getValue();
			String system = toStringOrNull(theSystem);
			valueSetIds = findValueSetIdsContainingSystemAndCode(code, system);
		}

		for (IIdType nextId : valueSetIds) {
			ValueSet expansion = expand(nextId, null);
			List<ValueSetExpansionContainsComponent> contains = expansion.getExpansion().getContains();
			ValidateCodeResult result = validateCodeIsInContains(contains, toStringOrNull(theSystem), toStringOrNull(theCode), theCoding, theCodeableConcept);
			if (result != null) {
				if (theDisplay != null && isNotBlank(theDisplay.getValue()) && isNotBlank(result.getDisplay())) {
					if (!theDisplay.getValue().equals(result.getDisplay())) {
						return new ValidateCodeResult(false, "Display for code does not match", result.getDisplay());
					}
				}
				return result;
			}
		}

		return new ValidateCodeResult(false, "Code not found", null);
	}

	private List<IIdType> findValueSetIdsContainingSystemAndCode(String theCode, String theSystem) {
		if (theSystem != null && theSystem.startsWith("http://hl7.org/fhir/")) {
			return Collections.singletonList((IIdType)new IdType(theSystem));
		}
		
		List<IIdType> valueSetIds;
		Set<Long> ids = searchForIds(ValueSet.SP_CODE, new TokenParam(theSystem, theCode));
		valueSetIds = new ArrayList<IIdType>();
		for (Long next : ids) {
			valueSetIds.add(new IdType("ValueSet", next));
		}
		return valueSetIds;
	}

	private static boolean multiXor(boolean... theValues) {
		int count = 0;
		for (int i = 0; i < theValues.length; i++) {
			if (theValues[i]) {
				count++;
			}
		}
		return count == 1;
	}

	private String toStringOrNull(IPrimitiveType<String> thePrimitive) {
		return thePrimitive != null ? thePrimitive.getValue() : null;
	}

	private ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.ValidateCodeResult validateCodeIsInContains(List<ValueSetExpansionContainsComponent> contains, String theSystem, String theCode, Coding theCoding,
			CodeableConcept theCodeableConcept) {
		for (ValueSetExpansionContainsComponent nextCode : contains) {
			ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.ValidateCodeResult result = validateCodeIsInContains(nextCode.getContains(), theSystem, theCode, theCoding, theCodeableConcept);
			if (result != null) {
				return result;
			}

			String system = nextCode.getSystem();
			String code = nextCode.getCode();

			if (isNotBlank(theCode)) {
				if (theCode.equals(code) && (isBlank(theSystem) || theSystem.equals(system))) {
					return new ValidateCodeResult(true, "Validation succeeded", nextCode.getDisplay());
				}
			} else if (theCoding != null) {
				if (StringUtils.equals(system, theCoding.getSystem()) && StringUtils.equals(code, theCoding.getCode())) {
					return new ValidateCodeResult(true, "Validation succeeded", nextCode.getDisplay());
				}
			} else {
				for (Coding next : theCodeableConcept.getCoding()) {
					if (StringUtils.equals(system, next.getSystem()) && StringUtils.equals(code, next.getCode())) {
						return new ValidateCodeResult(true, "Validation succeeded", nextCode.getDisplay());
					}
				}
			}

		}

		return null;
	}

	@Override
	public ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.LookupCodeResult lookupCode(IPrimitiveType<String> theCode, IPrimitiveType<String> theSystem, Coding theCoding) {
		boolean haveCoding = theCoding != null && isNotBlank(theCoding.getSystem()) && isNotBlank(theCoding.getCode());
		boolean haveCode = theCode != null && theCode.isEmpty() == false;
		boolean haveSystem = theSystem != null && theSystem.isEmpty() == false;

		if (!haveCoding && !(haveSystem && haveCode)) {
			throw new InvalidRequestException("No code, coding, or codeableConcept provided to validate");
		}
		if (!multiXor(haveCoding, (haveSystem && haveCode)) || (haveSystem != haveCode)) {
			throw new InvalidRequestException("$lookup can only validate (system AND code) OR (coding.system AND coding.code)");
		}

		String code;
		String system;
		if (haveCoding) {
			code = theCoding.getCode();
			system = theCoding.getSystem();
		} else {
			code = theCode.getValue();
			system = theSystem.getValue();
		}
		
		List<IIdType> valueSetIds = findValueSetIdsContainingSystemAndCode(code, system);
		for (IIdType nextId : valueSetIds) {
			ValueSet expansion = expand(nextId, null);
			List<ValueSetExpansionContainsComponent> contains = expansion.getExpansion().getContains();
			ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.LookupCodeResult result = lookup(contains, system, code);
			if (result != null) {
				return result;
			}
		}
		
		LookupCodeResult retVal = new LookupCodeResult();
		retVal.setFound(false);
		retVal.setSearchedForCode(code);
		retVal.setSearchedForSystem(system);
		return retVal;
	}

	private ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.LookupCodeResult lookup(List<ValueSetExpansionContainsComponent> theContains, String theSystem, String theCode) {
		for (ValueSetExpansionContainsComponent nextCode : theContains) {

				String system = nextCode.getSystem();
				String code = nextCode.getCode();
				if (theSystem.equals(system) && theCode.equals(code)) {
					ca.uhn.fhir.jpa.dao.IFhirResourceDaoValueSet.LookupCodeResult retVal = new LookupCodeResult();
					retVal.setSearchedForCode(code);
					retVal.setSearchedForSystem(system);
					retVal.setFound(true);
					if (nextCode.getAbstractElement() != null) {
						retVal.setCodeIsAbstract(nextCode.getAbstractElement().booleanValue());
					}
					retVal.setCodeDisplay(nextCode.getDisplay());
					retVal.setCodeSystemVersion(nextCode.getVersion());
					retVal.setCodeSystemDisplayName("Unknown"); // TODO: implement
					return retVal;
				}

			}

		return null;
	}

}