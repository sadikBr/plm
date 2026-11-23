package com.plm.office.domain

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The document properties of Word, Excel and PowerPoint files (Office Open XML, roadmap 8.4):
 * the core ones in `docProps/core.xml` (title, subject, author, keywords, description, category,
 * status) and the custom ones in `docProps/custom.xml`. Read them; write them back.
 */
internal object OfficeProperties {
    private const val CP = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val CUSTOM = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"
    private const val VT = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
    private const val CUSTOM_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties"
    private const val CUSTOM_TYPE = "application/vnd.openxmlformats-officedocument.custom-properties+xml"
    private const val FMTID = "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}"

    /** Core properties by the names the admin mapping uses, and their XML element. */
    val CORE: Map<String, Pair<String, String>> =
        linkedMapOf(
            "title" to (DC to "dc:title"),
            "subject" to (DC to "dc:subject"),
            "author" to (DC to "dc:creator"),
            "keywords" to (CP to "cp:keywords"),
            "description" to (DC to "dc:description"),
            "category" to (CP to "cp:category"),
            "status" to (CP to "cp:contentStatus"),
        )

    private val EXTENSIONS = setOf("docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "dotx", "xltx", "potx")

    fun isOffice(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    private fun parts(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = zip.readAllBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    private fun builder() =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }.newDocumentBuilder()

    private fun parse(bytes: ByteArray): Document = builder().parse(ByteArrayInputStream(bytes))

    private fun serialize(doc: Document): ByteArray {
        val out = ByteArrayOutputStream()
        TransformerFactory
            .newInstance()
            .newTransformer()
            .apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.STANDALONE, "yes")
            }.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    /** Core properties (by the names of [CORE]) and custom ones (by their own names), non-blank only. */
    fun read(bytes: ByteArray): Map<String, String> {
        val parts =
            try {
                parts(bytes)
            } catch (e: Exception) {
                return emptyMap()
            }
        val out = linkedMapOf<String, String>()
        parts["docProps/core.xml"]?.let { xml ->
            val doc = parse(xml)
            for ((name, element) in CORE) {
                val (ns, qname) = element
                // The first one with a value: some writers leave empty duplicates.
                val list = doc.getElementsByTagNameNS(ns, qname.substringAfter(':'))
                (0 until list.length)
                    .map { list.item(it).textContent.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.let { out[name] = it }
            }
        }
        parts["docProps/custom.xml"]?.let { xml ->
            val props = parse(xml).getElementsByTagNameNS(CUSTOM, "property")
            for (i in 0 until props.length) {
                val p = props.item(i) as Element
                val value = p.textContent.trim()
                if (value.isNotEmpty()) out.putIfAbsent(p.getAttribute("name"), value)
            }
        }
        return out
    }

    /** The file with [values] written into its properties (core names into core.xml, the rest as custom ones). */
    fun write(
        bytes: ByteArray,
        values: Map<String, String>,
    ): ByteArray {
        if (values.isEmpty()) return bytes
        val parts = parts(bytes)
        val core = values.filterKeys { it in CORE }
        val custom = values.filterKeys { it !in CORE }
        if (core.isNotEmpty()) {
            val doc =
                parts["docProps/core.xml"]?.let(::parse)
                    ?: builder().newDocument().also {
                        it.appendChild(it.createElementNS(CP, "cp:coreProperties"))
                    }
            val root = doc.documentElement
            for ((name, value) in core) {
                val (ns, qname) = CORE.getValue(name)
                val el =
                    (doc.getElementsByTagNameNS(ns, qname.substringAfter(':')).item(0) as Element?)
                        ?: doc.createElementNS(ns, qname).also(root::appendChild)
                el.textContent = value
            }
            parts["docProps/core.xml"] = serialize(doc)
        }
        if (custom.isNotEmpty()) {
            val doc =
                parts["docProps/custom.xml"]?.let(::parse)
                    ?: builder().newDocument().also {
                        val root = it.createElementNS(CUSTOM, "Properties")
                        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:vt", VT)
                        it.appendChild(root)
                    }
            val root = doc.documentElement
            val existing = root.getElementsByTagNameNS(CUSTOM, "property")
            var nextPid = 2
            val byName = mutableMapOf<String, Element>()
            for (i in 0 until existing.length) {
                val p = existing.item(i) as Element
                byName[p.getAttribute("name")] = p
                nextPid = maxOf(nextPid, (p.getAttribute("pid").toIntOrNull() ?: 1) + 1)
            }
            for ((name, value) in custom) {
                val p =
                    byName[name] ?: doc.createElementNS(CUSTOM, "property").also {
                        it.setAttribute("fmtid", FMTID)
                        it.setAttribute("pid", (nextPid++).toString())
                        it.setAttribute("name", name)
                        root.appendChild(it)
                    }
                while (p.firstChild != null) p.removeChild(p.firstChild)
                p.appendChild(doc.createElementNS(VT, "vt:lpwstr").also { it.textContent = value })
            }
            if ("docProps/custom.xml" !in parts) register(parts)
            parts["docProps/custom.xml"] = serialize(doc)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A new custom.xml needs its content type and a package relationship. */
    private fun register(parts: MutableMap<String, ByteArray>) {
        parts["[Content_Types].xml"]?.let { xml ->
            val doc = parse(xml)
            val root = doc.documentElement
            val override = doc.createElementNS(root.namespaceURI, "Override")
            override.setAttribute("PartName", "/docProps/custom.xml")
            override.setAttribute("ContentType", CUSTOM_TYPE)
            root.appendChild(override)
            parts["[Content_Types].xml"] = serialize(doc)
        }
        parts["_rels/.rels"]?.let { xml ->
            val doc = parse(xml)
            val root = doc.documentElement
            val rels = root.getElementsByTagNameNS(root.namespaceURI, "Relationship")
            val ids = (0 until rels.length).map { (rels.item(it) as Element).getAttribute("Id") }.toSet()
            var n = rels.length + 1
            while ("rId$n" in ids) n++
            val rel = doc.createElementNS(root.namespaceURI, "Relationship")
            rel.setAttribute("Id", "rId$n")
            rel.setAttribute("Type", CUSTOM_REL)
            rel.setAttribute("Target", "docProps/custom.xml")
            root.appendChild(rel)
            parts["_rels/.rels"] = serialize(doc)
        }
    }
}
