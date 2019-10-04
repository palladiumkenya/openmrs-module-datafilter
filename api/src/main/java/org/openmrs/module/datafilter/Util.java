/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.datafilter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.hibernate.annotations.FilterDefs;
import org.hibernate.annotations.Filters;
import org.hibernate.annotations.ParamDef;
import org.hibernate.search.annotations.FullTextFilterDefs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.module.datafilter.annotations.AggregateAnnotation;
import org.openmrs.module.datafilter.annotations.FilterAnnotation;
import org.openmrs.module.datafilter.annotations.FilterDefAnnotation;
import org.openmrs.module.datafilter.annotations.FullTextFilterDefAnnotation;
import org.openmrs.module.datafilter.annotations.ParamDefAnnotation;
import org.openmrs.module.datafilter.registration.FilterParameter;
import org.openmrs.module.datafilter.registration.FilterRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Util {
	
	private static final Logger log = LoggerFactory.getLogger(Util.class);
	
	private static List<FilterRegistration> filterRegistrations;
	
	private static List<FilterRegistration> getFilterRegistrations() {
		if (filterRegistrations == null) {
			filterRegistrations = loadFilterRegistrations();
		}
		
		return filterRegistrations;
	}
	
	/**
	 * Adds the defined filter annotations to persistent classes mapped with JPA annotations that need
	 * to be filtered.
	 */
	protected static void setupFilters() {
		if (log.isInfoEnabled()) {
			log.info("Registering filters");
		}
		
		for (FilterRegistration registration : getFilterRegistrations()) {
			ParamDef[] paramDefs = null;
			if (CollectionUtils.isNotEmpty(registration.getParameters())) {
				paramDefs = new ParamDef[registration.getParameters().size()];
				int index = 0;
				for (FilterParameter parameter : registration.getParameters()) {
					paramDefs[index] = new ParamDefAnnotation(parameter.getName(), parameter.getType());
					index++;
				}
			}
			
			registerFilter(registration.getTargetClass(),
			    new FilterDefAnnotation(registration.getName(), registration.getDefaultCondition(), paramDefs),
			    new FilterAnnotation(registration.getName(), registration.getCondition()));
		}
		
		registerFullTextFilter(Patient.class, new FullTextFilterDefAnnotation(
		        DataFilterConstants.LOCATION_BASED_FULL_TEXT_FILTER_NAME_PATIENT, PatientIdFullTextFilter.class));
		
		try {
			addAnnotationToField("encounters", Visit.class,
			    new FilterAnnotation(DataFilterConstants.ENC_TYPE_PRIV_BASED_FILTER_NAME_ENCOUNTER,
			            DataFilterConstants.FILTER_CONDITION_ENCOUNTER_ID));
		}
		catch (ReflectiveOperationException e) {
			throw new APIException(e);
		}
		
		if (log.isInfoEnabled()) {
			log.info("Successfully registered filters");
		}
	}
	
	/**
	 * Adds the specified {@link org.hibernate.annotations.FilterDef} and
	 * {@link org.hibernate.annotations.Filter} annotations to the specified class object.
	 * 
	 * @param entityClass the class object to add the annotations
	 * @param filterDefAnnotation the {@link org.hibernate.annotations.FilterDef} annotation to add
	 * @param filterAnnotation the {@link org.hibernate.annotations.Filter} annotation to add
	 */
	protected static void registerFilter(Class<?> entityClass, FilterDefAnnotation filterDefAnnotation,
	                                     FilterAnnotation filterAnnotation) {
		
		addAnnotationToGroup(entityClass, FilterDefs.class, filterDefAnnotation);
		addAnnotationToGroup(entityClass, Filters.class, filterAnnotation);
		if (filterAnnotation.name().startsWith(DataFilterConstants.LOCATION_BASED_FILTER_NAME_PREFIX)) {
			AccessUtil.recordLocationFilterRegistrationForClass(entityClass, filterAnnotation.name());
		} else if (filterAnnotation.name().startsWith(DataFilterConstants.ENC_TYPE_PRIV_BASED_FILTER_NAME_PREFIX)) {
			AccessUtil.recordEncounterTypeViewPrivilegeFilterRegistrationForClass(entityClass, filterAnnotation.name());
		}
	}
	
	/**
	 * Adds the specified {@link org.hibernate.search.annotations.FullTextFilterDef} annotation to the
	 * specified class object.
	 *
	 * @param entityClass the class object to add the annotation
	 * @param filterDefAnnotation the {@link org.hibernate.search.annotations.FullTextFilterDef}
	 *            annotation to add
	 */
	protected static void registerFullTextFilter(Class<?> entityClass, FullTextFilterDefAnnotation filterDefAnnotation) {
		addAnnotationToGroup(entityClass, FullTextFilterDefs.class, filterDefAnnotation);
	}
	
	/**
	 * Utility method that adds a grouped annotation to it's containing aggregate annotation.
	 * 
	 * @param entityClass the class that has the aggregate annotation
	 * @param aggregateAnnotationClass the aggregate annotation type
	 * @param toAdd the grouped annotation instance to add
	 * @param <A>
	 */
	private static <A extends Annotation> void addAnnotationToGroup(Class<?> entityClass, Class<A> aggregateAnnotationClass,
	                                                                Object toAdd) {
		
		A aggregateAnnotation = entityClass.getAnnotation(aggregateAnnotationClass);
		((AggregateAnnotation) aggregateAnnotation).add(toAdd);
	}
	
	/**
	 * Adds the specified annotation to the specified class
	 * 
	 * @param clazz the class to add the annotation
	 * @param annotation the annotation to add
	 */
	protected static void addAnnotationToClass(Class<?> clazz, Annotation annotation) throws ReflectiveOperationException {
		
		final String annotationName = annotation.annotationType().getName();
		if (log.isDebugEnabled()) {
			log.debug("Adding " + annotationName + " annotation to " + clazz);
		}
		
		Method method = Class.class.getDeclaredMethod("getDeclaredAnnotationMap");
		boolean accessible = method.isAccessible();
		try {
			method.setAccessible(true);
			Map<Class<? extends Annotation>, Annotation> map = (Map<Class<? extends Annotation>, Annotation>) method
			        .invoke(clazz);
			//TODO handle the case where the annotation is already present in case of module restart
			//TODO We also need to take care of FilterDefs and Filters annotations if present
			map.put(annotation.annotationType(), annotation);
			
			if (log.isDebugEnabled()) {
				log.debug("Successfully added " + annotationName + " annotation to " + clazz);
			}
		}
		catch (InvocationTargetException | IllegalAccessException e) {
			log.error("Failed to add " + annotationName + " annotation to " + clazz, e);
			throw e;
		}
		finally {
			//Always reset
			method.setAccessible(accessible);
		}
		
	}
	
	/**
	 * Adds an annotation to the field with a matching name in the specified class
	 * 
	 * @param fieldName the name of the field
	 * @param clazz the class to add the annotation
	 * @param annotation the annotation to add
	 */
	private static void addAnnotationToField(String fieldName, Class<?> clazz, Annotation annotation)
	    throws ReflectiveOperationException {
		
		final String annotationName = annotation.annotationType().getName();
		if (log.isDebugEnabled()) {
			log.debug("Adding " + annotationName + " annotation to " + clazz + "." + fieldName);
		}
		
		Field field = clazz.getDeclaredField(fieldName);
		boolean fieldAccessible = field.isAccessible();
		field.setAccessible(true);
		Method method = Field.class.getDeclaredMethod("declaredAnnotations");
		boolean methodAccessible = method.isAccessible();
		method.setAccessible(true);
		try {
			Map<Class<? extends Annotation>, Annotation> map = (Map<Class<? extends Annotation>, Annotation>) method
			        .invoke(field);
			
			//TODO handle the case where the annotation is already present in case of module restart
			//TODO We also need to take care of Filters annotations if present
			map.put(annotation.annotationType(), annotation);
			
			if (log.isDebugEnabled()) {
				log.debug("Successfully added " + annotationName + " annotation to " + clazz);
			}
		}
		catch (InvocationTargetException | IllegalAccessException e) {
			log.error("Failed to add " + annotationName + " annotation to " + clazz + "." + annotationName, e);
			throw e;
		}
		finally {
			//Always reset
			field.setAccessible(fieldAccessible);
			method.setAccessible(methodAccessible);
		}
	}
	
	protected static List<FilterRegistration> loadFilterRegistrations() {
		PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
		ObjectMapper mapper = new ObjectMapper();
		List<FilterRegistration> registrations = new ArrayList();
		try {
			Resource[] resources = resourceResolver.getResources("classpath*:/filters/*filters.json");
			for (Resource resource : resources) {
				JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, FilterRegistration.class);
				registrations.addAll(mapper.readValue(resource.getInputStream(), type));
			}
			
			return registrations;
		}
		catch (IOException e) {
			throw new APIException("Failed to load some filter registrations", e);
		}
	}
	
}
