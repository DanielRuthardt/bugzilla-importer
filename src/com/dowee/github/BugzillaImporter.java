/**
 * Copyright (c) 2010, dowee it solutions GmbH
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *  - Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  - Neither the name of the dowee it solutions GmbH nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package com.dowee.github;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.xmlbeans.XmlException;

import noNamespace.BugzillaDocument;
import noNamespace.BugDocument.Bug;
import noNamespace.LongDescDocument.LongDesc;

/**
 * Imports Bugzilla issue reports, which have been exported to XML, into GitHub Issues.
 * 
 * This importer is written for importing open issue reports. That said, it should import
 * closed issue reports fine as well, but the resulting GitHub Issue will be open nevertheless
 * and the resolution information will not be imported and be lost.
 * 
 * GitHub Issues API information: http://develop.github.com/p/issues.html
 * XML binding generated with Apache XMLBeans (http://xmlbeans.apache.org/)
 * XSD schema converted from http://dev.helma.org/bugs/bugzilla.dtd
 */
public class BugzillaImporter {
	
	private static String _repository;
	private static String _user;
	private static String _apiToken;
	
	private static void printUsage() {
		System.err.println("Usage:");
		System.err.println("");
		System.err.println("	BugzillaImporter xml_file git_repository user api_token");
		System.err.println("");
		System.err.println("Example:");
		System.err.println("");
		System.err.println("	BugzillaImporter issues.xml test/test test hfei38dkej94fhgja239f9g0hkxmdewd");
	}
	
	public static void main(String[] args) throws ClientProtocolException, URISyntaxException, IOException {
		if (args.length != 4) {
			printUsage();
			System.exit(1);
		}
		
		File file = new File(args[0]);
		if (!file.exists() || !file.canRead()) {
			printUsage();
			System.exit(2);
		}
		
		BugzillaImporter._repository = args[1];
		BugzillaImporter._user = args[2];
		BugzillaImporter._apiToken = args[3];		
		 
		BugzillaDocument bugzilla = null;
		try {
			bugzilla = BugzillaDocument.Factory.parse(file);
		} catch (XmlException e) {
			e.printStackTrace();
			printUsage();
			System.exit(3);
		} catch (IOException e) {
			e.printStackTrace();
			printUsage();
			System.exit(4);
		}

		Bug[] bugs = bugzilla.getBugzilla().getBugArray();
		for (int i = 0; i < bugs.length; i++) {
			createIssue(bugs[i]);
		}
	}
	
	public static String post(String url, ArrayList<NameValuePair> params) throws URISyntaxException, ClientProtocolException, IOException {
		HttpPost post = new HttpPost();
		post.setURI(URIUtils.createURI("http", "github.com", 80, "api/v2/yaml/" + url, "", null));
		
		params.add(new BasicNameValuePair("login", BugzillaImporter._user));
		params.add(new BasicNameValuePair("token", BugzillaImporter._apiToken));
		UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, "UTF-8");
		post.setEntity(entity);
		
		DefaultHttpClient http = new DefaultHttpClient();		
		HttpResponse response = http.execute(post);
		
		return EntityUtils.toString(response.getEntity());
	}
	
	public static void createIssue(Bug issue) throws ClientProtocolException, URISyntaxException, IOException {
		String title = "#" + issue.getBugId().getDomNode().getFirstChild().getNodeValue() + ": "
			+ issue.getShortDesc().getDomNode().getFirstChild().getNodeValue();
		
		ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("title", title));
		params.add(new BasicNameValuePair("body", 
				issue.getReporter().getDomNode().getAttributes().getNamedItem("name").getNodeValue()
				+ " (" + issue.getReporter().getDomNode().getFirstChild().getNodeValue() + ") reported on "
				+ issue.getCreationTs().getDomNode().getFirstChild().getNodeValue() + "\n\n<pre>"
				+ issue.getLongDescArray()[0].getThetext().getDomNode().getFirstChild().getNodeValue()
				+ "</pre>"));
		
		System.out.println("Creating issue: " + title);
		String response = post("issues/open/" + BugzillaImporter._repository, params);
		String id = response.substring(response.indexOf("number: ")).substring(8, response.substring(response.indexOf("number: ")).indexOf("\n"));
		
		String[] labels = new String[2];
		labels[0] = issue.getComponent().getDomNode().getFirstChild().getNodeValue();
		labels[1] = issue.getBugSeverity().getDomNode().getFirstChild().getNodeValue();

		addLabel(id, "Bugzilla");
		for (int j = 0; j <labels.length; j++) {
			addLabel(id, labels[j]);
		}
		
		LongDesc[] comments = issue.getLongDescArray();
		for (int j = 1; j < comments.length; j++) {
			addComment(id, comments[j]);
		}
	}
	
	public static void addLabel(String id, String label) throws ClientProtocolException, URISyntaxException, IOException {
		label = label.replaceAll("[^a-zA-Z0-9 ]", "").replace(" ", "_");
		
		ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
		System.out.println("Adding label: " + label);
		post("issues/label/add/" + BugzillaImporter._repository + "/" + URLEncoder.encode(label, "utf-8") + "/" + id, params);
	}
	
	public static void addComment(String id, LongDesc comment) throws ClientProtocolException, URISyntaxException, IOException {		
		ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("comment", 
				comment.getWho().getDomNode().getAttributes().getNamedItem("name").getNodeValue()
				+ " (" + comment.getWho().getDomNode().getFirstChild().getNodeValue() + ") wrote on "
				+ comment.getBugWhen().getDomNode().getFirstChild().getNodeValue() + "\n\n<pre>"
				+ comment.getThetext().getDomNode().getFirstChild().getNodeValue()
				+ "</pre>"));
		
		System.out.println("Adding comment");
		post("issues/comment/" + BugzillaImporter._repository + "/" + id, params);
	}

}