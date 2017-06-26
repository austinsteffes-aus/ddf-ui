/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */

package ddf.services.schematron;

import java.io.File;
import java.io.StringReader;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.lang.StringUtils;
import org.codice.ddf.platform.util.XMLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import com.google.common.collect.ImmutableSet;

import ddf.catalog.data.Metacard;
import ddf.catalog.util.Describable;
import ddf.catalog.validation.MetacardValidator;
import ddf.catalog.validation.ReportingMetacardValidator;
import ddf.catalog.validation.ValidationException;
import ddf.catalog.validation.impl.ValidationExceptionImpl;
import ddf.catalog.validation.impl.report.MetacardValidationReportImpl;
import ddf.catalog.validation.impl.violation.ValidationViolationImpl;
import ddf.catalog.validation.report.MetacardValidationReport;
import ddf.catalog.validation.violation.ValidationViolation;

import net.sf.saxon.Configuration;
import net.sf.saxon.TransformerFactoryImpl;

/**
 * This pre-ingest service provides validation of an ingested XML document against a Schematron
 * schema file.
 * <p>
 * When this service is instantiated at deployment time to the OSGi container it goes through 3
 * different preprocessing stages on the Schematron schema file. (These steps are required by the
 * ISO Schematron implementation)
 * <ol>
 * <li>1. Preprocess the Schematron schema with iso_dsdl_include.xsl. This is a macro processor to
 * assemble the schema from various parts.</li>
 * <li>2. Preprocess the output from stage 1 with iso_abstract_expand.xsl. This is a macro processor
 * to convert abstract patterns to real patterns.</li>
 * <li>3. Compile the Schematron schema into an XSLT script. This will use iso_svrl_for_xslt2.xsl
 * (which in turn invokes iso_schematron_skeleton_for_saxon.xsl)</li>
 * </ol>
 * <p>
 * When XML documents are ingested, this service will run the XSLT generated by stage 3 against the
 * XML document, validating it against the "compiled" Schematron schema file.
 * <p>
 * This service is using the SVRL script, hence the output of the validation will be an
 * SVRL-formatted XML document.
 *
 * @author rodgersh
 * @see <a href="http://www.schematron.com">Schematron</a>
 */
public class SchematronValidationService
        implements MetacardValidator, Describable, ReportingMetacardValidator {

    public static final String DEFAULT_THREAD_POOL_SIZE = "16";

    private static final String SCHEMATRON_BASE_FOLDER = Paths.get(System.getProperty("ddf.home"),
            "schematron")
            .toString();

    private static final Logger LOGGER = LoggerFactory.getLogger(SchematronValidationService.class);

    private static final XMLUtils XML_UTILS = XMLUtils.getInstance();

    private TransformerFactory transformerFactory;

    private Vector<String> warnings;

    private int priority = 10;

    private SchematronReport schematronReport;

    private List<String> schematronFileNames;

    private boolean suppressWarnings = false;

    private String namespace;

    private String id;

    private ExecutorService pool = getThreadPool();

    private List<Future<Templates>> validators = new ArrayList<>();

    private static ExecutorService getThreadPool() throws NumberFormatException {
        Integer threadPoolSize = Integer.parseInt(System.getProperty(
                "org.codice.ddf.system.threadPoolSize",
                DEFAULT_THREAD_POOL_SIZE));
        return Executors.newFixedThreadPool(threadPoolSize);
    }

    /**
     * Replace tabs, literal carriage returns, and newlines with a single whitespace
     *
     * @param input
     * @return
     */
    static String sanitize(final String input) {
        return input.replaceAll("[\t \r\n]+", " ")
                .trim();
    }

    public void init() throws SchematronInitializationException {
        if (transformerFactory == null) {
            transformerFactory =
                    TransformerFactory.newInstance(TransformerFactoryImpl.class.getName(),
                            SchematronValidationService.class.getClassLoader());
        }

        // DDF-855: set ErrorListener to catch any warnings/errors during loading of the
        // ruleset file and log (vs. Saxon default of writing to console) the warnings/errors
        Configuration config = ((TransformerFactoryImpl) transformerFactory).getConfiguration();
        config.setErrorListener(new SaxonErrorListener(schematronFileNames));

        updateValidators();
    }

    private void updateValidators() throws SchematronInitializationException {
        validators.clear();
        for (String schematronFileName : schematronFileNames) {
            FutureTask<Templates> task = new FutureTask<Templates>(() -> {
                return compileSchematronRules(schematronFileName);
            });
            validators.add(task);
            pool.submit(task);
        }
    }

    private Templates compileSchematronRules(String schematronFileName)
            throws SchematronInitializationException {

        Templates template;
        File schematronFile = new File(schematronFileName);
        if (!schematronFile.exists()) {
            throw new SchematronInitializationException(
                    "Could not locate schematron file " + schematronFileName);
        }

        try {
            URL schUrl = schematronFile.toURI()
                    .toURL();
            Source schSource = new StreamSource(schUrl.toString());

            // Stage 1: Perform inclusion expansion on Schematron schema file
            DOMResult stage1Result = performStage(schSource,
                    getClass().getClassLoader()
                            .getResource("iso-schematron/iso_dsdl_include.xsl"));
            DOMSource stage1Output = new DOMSource(stage1Result.getNode());

            // Stage 2: Perform abstract expansion on output file from Stage 1
            DOMResult stage2Result = performStage(stage1Output,
                    getClass().getClassLoader()
                            .getResource("iso-schematron/iso_abstract_expand.xsl"));
            DOMSource stage2Output = new DOMSource(stage2Result.getNode());

            // Stage 3: Compile the .sch rules that have been prepocessed by Stages 1 and 2 (i.e.,
            // the output of Stage 2)
            DOMResult stage3Result = performStage(stage2Output,
                    getClass().getClassLoader()
                            .getResource("iso-schematron/iso_svrl_for_xslt2.xsl"));
            DOMSource stage3Output = new DOMSource(stage3Result.getNode());

            // Setting the system ID let's us resolve relative paths in the schematron files.
            // We need the URL string so that the string is properly formatted (e.g. space = %20).
            stage3Output.setSystemId(schUrl.toString());
            template = transformerFactory.newTemplates(stage3Output);
        } catch (Exception e) {
            throw new SchematronInitializationException(
                    "Error trying to create SchematronValidationService using sch file "
                            + schematronFileName,
                    e);
        }

        return template;
    }

    private DOMResult performStage(Source input, URL preprocessorUrl)
            throws TransformerException, ParserConfigurationException,
            SchematronInitializationException {

        Source preprocessorSource = new StreamSource(preprocessorUrl.toString());

        // Initialize container for warnings we may receive during transformation of input
        warnings = new Vector<>();

        Transformer transformer = transformerFactory.newTransformer(preprocessorSource);

        // Setup an error listener to catch warnings and errors generated during transformation
        transformer.setErrorListener(new Listener());

        // Transform the input using the preprocessor's transformer, capturing the output in a DOM
        DOMResult domResult = new DOMResult();
        transformer.transform(input, domResult);

        return domResult;
    }

    public void setSuppressWarnings(boolean suppressWarnings) {
        this.suppressWarnings = suppressWarnings;
    }

    public void setSchematronFileNames(List<String> schematronFileNames)
            throws SchematronInitializationException {
        this.schematronFileNames = new ArrayList<>();
        for (String filename : schematronFileNames) {
            String fullpath = Paths.get(filename)
                    .toString();
            if (!Paths.get(filename)
                    .isAbsolute()) {
                fullpath = Paths.get(SCHEMATRON_BASE_FOLDER, fullpath)
                        .toString();
            }
            this.schematronFileNames.add(fullpath);
        }
        if (transformerFactory != null) {
            updateValidators();
        }
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public void setPriority(int priority) {
        this.priority = priority;

        // 1 is the highest priority, 100 the lowest
        if (this.priority > 100) {
            this.priority = 100;
        } else if (this.priority < 1) {
            this.priority = 1;
        }
    }

    @Override
    public void validate(Metacard metacard) throws ValidationException {

        MetacardValidationReport report = generateReport(metacard);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        report.getMetacardValidationViolations()
                .forEach(violation -> {
                    if (violation.getSeverity() == ValidationViolation.Severity.ERROR) {
                        errors.add(violation.getMessage());
                    } else {
                        warnings.add(violation.getMessage());
                    }
                });

        SchematronValidationException exception = new SchematronValidationException(
                "Schematron validation failed",
                errors,
                warnings);

        if (!errors.isEmpty()) {
            throw exception;
        }

        if (!suppressWarnings && !warnings.isEmpty()) {
            throw exception;
        }

    }

    private MetacardValidationReport generateReport(Metacard metacard)
            throws ValidationExceptionImpl {
        MetacardValidationReportImpl report = new MetacardValidationReportImpl();
        Set<String> attributes = ImmutableSet.of("metadata");
        String metadata = metacard.getMetadata();
        boolean canBeValidated = !(StringUtils.isEmpty(metadata) || (namespace != null
                && !namespace.equals(XML_UTILS.getRootNamespace(metadata))));
        if (canBeValidated) {
            try {
                for (Future<Templates> validator : validators) {
                    schematronReport = generateReport(metadata,
                            validator.get(10, TimeUnit.MINUTES));
                    schematronReport.getErrors()
                            .forEach(errorMsg -> report.addMetacardViolation(new ValidationViolationImpl(
                                    attributes,
                                    sanitize(errorMsg),
                                    ValidationViolation.Severity.ERROR)));
                    schematronReport.getWarnings()
                            .forEach(warningMsg -> report.addMetacardViolation(new ValidationViolationImpl(
                                    attributes,
                                    sanitize(warningMsg),
                                    ValidationViolation.Severity.WARNING)));
                }
            } catch (TimeoutException | ExecutionException | InterruptedException e) {
                throw new ValidationExceptionImpl(e);
            }
        }
        return report;
    }

    private SchematronReport generateReport(String metadata, Templates validator)
            throws SchematronValidationException {

        XMLReader xmlReader = null;
        try {
            XMLReader xmlParser = XML_UTILS.getSecureXmlParser();
            xmlReader = new XMLFilterImpl(xmlParser);
        } catch (SAXException e) {
            throw new SchematronValidationException(e);
        }

        SchematronReport report;
        try {
            Transformer transformer = validator.newTransformer();
            DOMResult schematronResult = new DOMResult();
            transformer.transform(new SAXSource(xmlReader,
                    new InputSource(new StringReader(metadata))), schematronResult);
            report = new SvrlReport(schematronResult);
        } catch (TransformerException e) {
            throw new SchematronValidationException(
                    "Could not setup validator to perform validation.",
                    e);
        }
        return report;
    }

    @Override
    public String getVersion() {
        return null;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getTitle() {
        return null;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getOrganization() {
        return null;
    }

    @Override
    public Optional<MetacardValidationReport> validateMetacard(Metacard metacard) {
        try {
            return Optional.of(generateReport(metacard));
        } catch (ValidationExceptionImpl e) {
            LOGGER.warn("Exception validating metacard ID {}", metacard.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * The Listener class which catches Saxon configuration errors.
     * <p>
     * DDF-855: These warnings and errors are logged so that they are
     * not displayed on the console.
     */
    private static class SaxonErrorListener implements ErrorListener {

        private List<String> schematronFileNames;

        public SaxonErrorListener(List<String> schematronFileNames) {
            this.schematronFileNames = schematronFileNames;
        }

        @Override
        public void warning(TransformerException e) throws TransformerException {
            LOGGER.debug("Transformer warning: '{}' on file: {}",
                    e.getMessage(),
                    this.schematronFileNames);
            LOGGER.debug("Saxon exception", e);
        }

        @Override
        public void error(TransformerException e) throws TransformerException {
            LOGGER.debug("Transformer warning: '{}' on file: {}",
                    e.getMessage(),
                    this.schematronFileNames);
            LOGGER.debug("Saxon exception", e);
        }

        @Override
        public void fatalError(TransformerException e) throws TransformerException {
            LOGGER.info("Transformer error: (Schematron file = {}):", this.schematronFileNames, e);
        }
    }

    /**
     * The Listener class which catches xsl:messages during the transformation/stages of the
     * Schematron schema.
     */
    private class Listener implements ErrorListener {
        public void warning(TransformerException e) throws TransformerException {
            warnings.add(e.getMessage());
        }

        public void error(TransformerException e) throws TransformerException {
            throw e;
        }

        public void fatalError(TransformerException e) throws TransformerException {
            throw e;
        }
    }
}
