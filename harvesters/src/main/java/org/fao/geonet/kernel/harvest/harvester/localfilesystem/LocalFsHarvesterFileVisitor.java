/*
 * Copyright (C) 2001-2016 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */

package org.fao.geonet.kernel.harvest.harvester.localfilesystem;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import jeeves.server.context.ServiceContext;
import org.apache.commons.lang.time.DateUtils;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.ISODate;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.kernel.datamanager.IMetadataManager;
import org.fao.geonet.kernel.datamanager.IMetadataSchemaUtils;
import org.fao.geonet.kernel.datamanager.IMetadataOperations;
import org.fao.geonet.kernel.datamanager.IMetadataUtils;
import org.fao.geonet.kernel.datamanager.IMetadataValidator;
import org.fao.geonet.kernel.harvest.BaseAligner;
import org.fao.geonet.kernel.harvest.harvester.CategoryMapper;
import org.fao.geonet.kernel.harvest.harvester.GroupMapper;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.kernel.mef.MEFLib;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.repository.Updater;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.fao.geonet.kernel.HarvestValidationEnum.NOVALIDATION;

/**
 * @author Jesse on 11/6/2014.
 */
class LocalFsHarvesterFileVisitor extends SimpleFileVisitor<Path> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Geonet.HARVESTER);

    private final LocalFilesystemParams params;
    private final LocalFilesystemHarvester harvester;
    private final HarvestResult result = new HarvestResult();
    private final MetadataRepository repo;
    private final ServiceContext context;
    private final AtomicBoolean cancelMonitor;
    private final BaseAligner aligner;
    private final CategoryMapper localCateg;
    private final GroupMapper localGroups;
    private final Set<Integer> listOfRecords = Sets.newHashSet();
    private final Set<Integer> listOfRecordsToIndex = Sets.newHashSet();
    private boolean transformIt = false;
    private Path thisXslt;
    private long startTime;

    @Autowired
    protected IMetadataManager mdManager;
    @Autowired
    protected IMetadataSchemaUtils mdSchemaUtils;
    @Autowired
    protected IMetadataUtils mdUtils;
    @Autowired
    protected IMetadataValidator mdValidator;
    @Autowired
    protected IMetadataOperations mdOperations;


    public LocalFsHarvesterFileVisitor(AtomicBoolean cancelMonitor, ServiceContext context, LocalFilesystemParams params, LocalFilesystemHarvester harvester) throws Exception {
        this.aligner = new BaseAligner(cancelMonitor) {};

        this.cancelMonitor = cancelMonitor;
        this.context = context;
        this.thisXslt = context.getAppPath().resolve(Geonet.Path.IMPORT_STYLESHEETS);
        if (!params.getImportXslt().equals("none")) {
            String xslPath = params.getImportXslt();
            if(!xslPath.endsWith(".xsl")) {
                xslPath += ".xsl";
            }
            thisXslt = thisXslt.resolve(xslPath);
            transformIt = true;
        }
        localCateg = new CategoryMapper(context);
        localGroups = new GroupMapper(context);
        this.params = params;
        this.harvester = harvester;
        this.repo = context.getBean(MetadataRepository.class);
        this.startTime = System.currentTimeMillis();
        this.mdManager = context.getApplicationContext().getBean(IMetadataManager.class);
        this.mdSchemaUtils = context.getApplicationContext().getBean(IMetadataSchemaUtils.class);
        this.mdOperations = context.getApplicationContext().getBean(IMetadataOperations.class);
        this.mdUtils = context.getApplicationContext().getBean(IMetadataUtils.class);
        this.mdValidator = context.getApplicationContext().getBean(IMetadataValidator.class);
        LOGGER.debug("Start visiting files at {}.", this.startTime);
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (cancelMonitor.get()) {
            return FileVisitResult.TERMINATE;
        }

        try {
            if (file != null &&
                file.getFileName() != null &&
                file.getFileName().toString() != null &&
                    (file.getFileName().toString().endsWith(".xml") || MEFLib.isValidArchiveExtensionForMEF(file.getFileName().toString()))) {

                result.totalMetadata++;



                if (LOGGER.isDebugEnabled() && result.totalMetadata % 1000 == 0) {
                    long elapsedTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);
                    LOGGER.debug("{} records inserted in {} s ({} records/s).", new Object[] {
                            result.totalMetadata,
                            elapsedTime,
                            result.totalMetadata / elapsedTime});
                }

                Path filePath = file.toAbsolutePath().normalize();
                if(MEFLib.isValidArchiveExtensionForMEF(file.getFileName().toString())) {
                    processMef(file, filePath);
                    return FileVisitResult.CONTINUE;
                }

                Element xml;
                try {
                    LOGGER.debug("reading file: {}", filePath);
                    xml = Xml.loadFile(file);
                } catch (JDOMException e) { // JDOM problem
                    LOGGER.debug("Error loading XML from file {}, ignoring", filePath);
                    e.printStackTrace();
                    result.badFormat++;
                    return FileVisitResult.CONTINUE; // skip this one
                } catch (Throwable e) { // some other error
                    LOGGER.debug("Error retrieving XML from file {}, ignoring", filePath);
                    e.printStackTrace();
                    result.unretrievable++;
                    return FileVisitResult.CONTINUE; // skip this one
                }


                // transform using importxslt if not none
                if (transformIt) {
                    try {
                        xml = Xml.transform(xml, thisXslt);
                    } catch (Exception e) {
                        LOGGER.debug("Cannot transform XML from file {}, ignoring. Error was: {}", filePath, e.getMessage());
                        result.badFormat++;
                        return FileVisitResult.CONTINUE; // skip this one
                    }
                }

                String schema = null;
                try {
                    schema = mdSchemaUtils.autodetectSchema(xml, null);
                } catch (Exception e) {
                    result.unknownSchema++;
                }
                if (schema == null) {
                    return FileVisitResult.CONTINUE;
                }

                try {
                    params.getValidate().validate(context, xml);
                } catch (Exception e) {
                    LOGGER.debug("Cannot validate XML from file {}, ignoring. Error was: {}", filePath, e.getMessage());
                    result.doesNotValidate++;
                    return FileVisitResult.CONTINUE; // skip this one
                }

                String uuid = getUuidFromFile(xml, filePath, schema);
                if (uuid == null || uuid.equals("")) {
                    result.badFormat++;
                    return FileVisitResult.CONTINUE;
                }

                String id = mdUtils.getMetadataId(uuid);
                if (id == null) {
                    // For new record change date will be the time of metadata xml date change or the date when
                    // the record was harvested (if can't be obtained the metadata xml date change)
                    String createDate;
                    // or the last modified date of the file
                    if (params.checkFileLastModifiedForUpdate) {
                        createDate = new ISODate(Files.getLastModifiedTime(file).toMillis(), false).getDateAndTime();
                    } else {
                        try {
                            createDate = mdUtils.extractDateModified(schema, xml);
                        } catch (Exception ex) {
                            LOGGER.error("LocalFilesystemHarvester - addMetadata - can't get metadata modified date for metadata uuid= {} " +
                                    "using current date for modified date", uuid);
                            createDate = new ISODate().toString();
                        }
                    }

                    LOGGER.debug("adding new metadata");
                    id = addMetadata(xml, schema, uuid, createDate);
                } else {
                    // Check last modified date of the file with the record change date
                    // to check if an update is required
                    if (params.checkFileLastModifiedForUpdate) {
                        Date fileDate = new Date(Files.getLastModifiedTime(file).toMillis());

                        final Metadata metadata = repo.findOne(id);
                        ISODate modified = new ISODate();
                        if (metadata != null && metadata.getDataInfo() != null) {
                            modified = metadata.getDataInfo().getChangeDate();
                        }

                        Date recordDate = modified.toDate();

                        String changeDate = new ISODate(fileDate.getTime(), false).getDateAndTime();

                        LOGGER.debug(" File date is: {} / record date is: ", filePath, modified);

                        if (DateUtils.truncate(recordDate, Calendar.SECOND)
                            .before(DateUtils.truncate(fileDate, Calendar.SECOND))) {
                            LOGGER.debug("  Db record is older than file. Updating record with id: {}", id);
                            updateMedata(xml, id, changeDate);
                        } else {
                            LOGGER.debug("  Db record is not older than last modified date of file. No need for update.");
                            result.unchangedMetadata++;
                        }
                    } else {
                        LOGGER.debug("  updating existing metadata, id is: {}", id);

                        String changeDate;

                        try {
                            changeDate = mdUtils.extractDateModified(schema, xml);
                        } catch (Exception ex) {
                            LOGGER.error("LocalFilesystemHarvester - updateMetadata - can't get metadata modified date for " +
                                "metadata id= {}, using current date for modified date", id);
                            changeDate = new ISODate().toString();
                        }

                        updateMedata(xml, id, changeDate);
                    }
                }
                listOfRecords.add(Integer.valueOf(id));
            }
        } catch (Throwable e) {
            LOGGER.error("An error occurred while harvesting a local file:{}. Error is: " + e.getMessage());
        }
        return FileVisitResult.CONTINUE;
    }

    private void processMef(Path file, Path filePath) {
        LOGGER.debug("reading file: {}", filePath);
        try {
            String xsl = params.getImportXslt();
            MEFLib.Version version = MEFLib.getMEFVersion(file);
            String fileType = version == MEFLib.Version.V1 ? "mef" : "mef2";
            String style = (xsl.equals("none") || xsl == null) ? "_none_" : xsl;
            MetadataType isTemplate = MetadataType.lookup(params.recordType);

            List<String> ids = MEFLib.doImport(
                    fileType,
                    MEFLib.UuidAction.OVERWRITE,
                    style,
                    params.getUuid(),
                    isTemplate,
                    Iterables.toArray(params.getCategories(), String.class),
                    params.getOwnerIdGroup(),
                    params.getValidate() != NOVALIDATION,
                    false, context, file);
            for (String id : ids) {
                LOGGER.debug("Metadata imported from MEF: {}", id);
                context.getBean(MetadataRepository.class).update(Integer.valueOf(id), new Updater<Metadata>() {
                    @Override
                    public void apply(@Nonnull final Metadata metadata) {
                       metadata.getHarvestInfo().setHarvested(true);
                       metadata.getHarvestInfo().setUuid(params.getUuid());
                    }
                });
                aligner.addPrivileges(id, params.getPrivileges(), localGroups, mdOperations, context);
                listOfRecordsToIndex.add(Integer.valueOf(id));
                listOfRecords.add(Integer.valueOf(id));
                result.addedMetadata++;
            }
        } catch (Exception e) {
            LOGGER.debug("Error retrieving MEF from file {}, ignoring", filePath);
            e.printStackTrace();
            result.unretrievable++;
        }
    }

    private String getUuidFromFile(Element xml, Path filePath, String schema) {
        String uuid = null;
        try {
            uuid = mdUtils.extractUUID(schema, xml);
        } catch (Exception e) {
            LOGGER.debug("Failed to extract metadata UUID for file {}" +
                " using XSL extract-uuid. The record is probably " +
                "a subtemplate. Will check uuid attribute on root element.", filePath);

            // Extract UUID from uuid attribute in subtemplates
            String uuidAttribute = xml.getAttributeValue("uuid");
            if (uuidAttribute != null) {
                LOGGER.debug("Found uuid attribute {} for file {}.", uuidAttribute, filePath);
                uuid = uuidAttribute;
            } else {
                // Assigning a new UUID
                uuid = UUID.randomUUID().toString();
                LOGGER.debug("No UUID found, the record will be assigned a random uuid {} for file {}.", uuid, filePath);
            }
        }
        return uuid;
    }

    private String addMetadata(Element xml, String schema, String uuid, String createDate) throws Exception {
        String id = harvester.addMetadata(xml, uuid, schema, localGroups, localCateg, createDate, aligner, false);
        listOfRecordsToIndex.add(Integer.valueOf(id));
        result.addedMetadata++;
        return id;
    }

    private void updateMedata(Element xml, String id, String changeDate) throws Exception {
        harvester.updateMetadata(xml, id, localGroups, localCateg, changeDate, aligner);
        listOfRecordsToIndex.add(Integer.valueOf(id));
        result.updatedMetadata++;
    }

    public HarvestResult getResult() {
        return result;
    }

    public Set<Integer> getListOfRecords() {
        return listOfRecords;
    }

    public Set<Integer> getListOfRecordsToIndex() {
        return listOfRecordsToIndex;
    }
}
