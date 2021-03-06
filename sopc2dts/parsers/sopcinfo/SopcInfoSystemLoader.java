/*
sopc2dts - Devicetree generation for Altera systems

Copyright (C) 2011 - 2012 Walter Goossens <waltergoossens@home.nl>

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
*/

package sopc2dts.parsers.sopcinfo;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Vector;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import sopc2dts.Logger;
import sopc2dts.Logger.LogLevel;
import sopc2dts.lib.Connection;
import sopc2dts.lib.AvalonSystem;
import sopc2dts.lib.SopcComponentLib;
import sopc2dts.lib.components.BasicComponent;
import sopc2dts.lib.components.Interface;
/** @brief System loader for sopcinfo files
 * 
 * This is the most important (and therefore best tested) loader. This loader 
 * creates a model in memory resembling the system as is described in the 
 * sopcinfo file. This model can the be used to generate different outputs, but
 * mainly device-trees.
 * 
 * @note This loader supports all versions of SOPCBuilder that output sopcinfo 
 * files directly but qsys is a bit more difficult. Versions prior 10.0 through 
 * 10.1 did not output the complete picture of subsystems so we search for qsys
 * files to load the internals of the subsystems. This seems to be fixed in 12.0
 *   
 * @author Walter Goossens
 *
 */
public class SopcInfoSystemLoader implements ContentHandler {
	/** @brief Minimal supported QuartusII version
	 * 
	 * QuartusII 8.1 is the first version to output an sopcinfo file from 
	 * sopcbuilder. Earlier versions might be supported through the ptf files
	 * generated by sopcbuilder loaded using the PtfSystemLoader
	 */
	public static final float MIN_SUPPORTED_VERSION	= 8.1f;
	/** @brief Highest supported/tested QuartusII version
	 * 
	 * This is the latest version that is tested against. Higher version might 
	 * very well work, but this can not be guaranteed. This number is just a 
	 * hint, higher versions will be parsed without complaining.
	 */
	public static final float MAX_SUPPORTED_VERSION	= 12.0f;
	float version = MIN_SUPPORTED_VERSION;
	private AvalonSystem currSystem;
	private BasicComponent currComp;
	private File sourceFile;
	/** @brief The XMLReader used when parsing the SopcInfo file
	 * 
	 * "Normally" you don't preserve a handle to a XMLReader object, but here we
	 * like to keep a reference so we can change the contentHandler while we are
	 * parsing.
	 */
	private XMLReader xmlReader;
	/** @brief List of connections in the system */
	protected Vector<Connection> vConnections = new Vector<Connection>();
	SopcComponentLib lib = SopcComponentLib.getInstance();
	private String uniqueID = "";
	private String currTag = "";
	
	public synchronized AvalonSystem loadSystem(File source)
	{
		try {
			InputSource in = new InputSource(new FileReader(source));
			sourceFile = source;
			xmlReader = XMLReaderFactory.createXMLReader();
			xmlReader.setContentHandler(this);
			xmlReader.parse(in);
			if(currSystem!=null)
			{
				connectComponents();
				currSystem.recheckComponents();
			}
		} catch (SAXException e) {
			Logger.logException(e);
			currSystem = null;
		} catch (FileNotFoundException e) {
			Logger.logException(e);
			currSystem = null;
		} catch (IOException e) {
			Logger.logException(e);
			currSystem = null;
		}
		return currSystem;
	}
	public void connectComponents()
	{
		Connection conn;
		while(vConnections.size()>0)
		{
			boolean foundDuplicate = false;
			conn = vConnections.firstElement();
			Interface intfM = conn.getMasterInterface();
			Interface intfS = conn.getSlaveInterface();
			//Check for duplicate
			if(intfM!=null)
			{
				for(Connection c : intfM.getConnections())
				{
					if((c.getMasterInterface() == intfM)&&(c.getSlaveInterface() == intfS))
					{
						foundDuplicate = true;
					}
				}
				if(!foundDuplicate)
				{
					intfM.getConnections().add(conn);
					if(intfS!=null)
					{
						intfS.getConnections().add(conn);
					}
				}
			}
			vConnections.remove(conn);
		}
	}

	public void startElement(String uri, String localName, String qName, 
			Attributes atts) throws SAXException {
		if(localName.equalsIgnoreCase("module"))
		{
			currComp = lib.getComponentForClass(atts.getValue("kind"),
					atts.getValue("name"), atts.getValue("version"));
			currSystem.addSystemComponent(currComp);
			@SuppressWarnings("unused")
			SopcInfoComponent c = new SopcInfoComponent(this, xmlReader, currComp);
		} else if(localName.equalsIgnoreCase("connection")) {
			@SuppressWarnings("unused")
			SopcInfoConnection conn = new SopcInfoConnection(this, xmlReader, 
					atts.getValue("kind"), this);
		} else if(localName.equalsIgnoreCase("plugin") ||
				localName.equalsIgnoreCase("parameter")) {
			@SuppressWarnings("unused")
			SopcInfoElementIgnoreAll ignore = new SopcInfoElementIgnoreAll(this, 
					xmlReader, localName);
		} else if(localName.equalsIgnoreCase("reportVersion") ||
				localName.equalsIgnoreCase("uniqueIdentifier")) {
			currTag = localName;
		} else if(localName.equalsIgnoreCase("EnsembleReport"))
		{
			currSystem = new AvalonSystem(atts.getValue("name"), 
					atts.getValue("version"), sourceFile);
		} else {
			Logger.logln("New element " + localName, LogLevel.DEBUG);
		}
	}
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if(localName.equals(currTag)) {
			currTag = null;
		} else if(localName.equalsIgnoreCase("module")) {
			currComp = null;
		} else if(localName.equalsIgnoreCase("EnsembleReport")) {
			Logger.logln("sopcinfo: Loading done", LogLevel.DEBUG);
		} else {
			Logger.logln("sopcinfo: Unexpected endtag: " + localName, LogLevel.DEBUG);
		}
	}
	
	public void characters(char[] ch, int start, int length) throws SAXException {
		if(currTag!=null)
		{
			if(currTag.equalsIgnoreCase("uniqueIdentifier"))
			{
				uniqueID = String.copyValueOf(ch, start, length);
			} else if(currTag.equalsIgnoreCase("reportVersion"))
			{
				currSystem.setVersion(String.copyValueOf(ch, start, length));
			}
		}
	}
	public void endDocument() throws SAXException {
	}
	public void endPrefixMapping(String arg0) throws SAXException {
	}
	public void ignorableWhitespace(char[] arg0, int arg1, int arg2)
			throws SAXException {
	}
	public void processingInstruction(String arg0, String arg1)
			throws SAXException {
	}
	public void setDocumentLocator(Locator arg0) {
	}
	public void skippedEntity(String arg0) throws SAXException {
	}
	public void startDocument() throws SAXException {
	}
	public void startPrefixMapping(String arg0, String arg1)
			throws SAXException {
	}
	public AvalonSystem getCurrSystem() {
		return currSystem;
	}
	public void setUniqueID(String uniqueID) {
		this.uniqueID = uniqueID;
	}
}
