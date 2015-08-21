/*
*Copyright (c) 2005-2013, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*WSO2 Inc. licenses this file to you under the Apache License,
*Version 2.0 (the "License"); you may not use this file except
*in compliance with the License.
*You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
*Unless required by applicable law or agreed to in writing,
*software distributed under the License is distributed on an
*"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*KIND, either express or implied.  See the License for the
*specific language governing permissions and limitations
*under the License.
*/
package org.wso2.carbon.identity.application.authentication.framework.model;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;

public class AuthenticationFrameworkWrapper extends HttpServletRequestWrapper
{
    private final Map<String, String[]> modifiableParameters;
    private Map<String, String[]> allParameters = null;
    private final Map<String, String> modifiableHeaders;

    /**
     * Create a new request wrapper that will merge additional parameters into
     * the request object without prematurely reading parameters from the
     * original request.
     *
     * @param request
     * @param additionalParams
     */
    public AuthenticationFrameworkWrapper(final HttpServletRequest request,
                                     final Map<String, String[]> additionalParams,final Map<String,String> additionalHeaders)
    {
        super(request);
        modifiableParameters = new TreeMap<String, String[]>();
        modifiableParameters.putAll(additionalParams);
        modifiableHeaders = new TreeMap<String, String>();
        modifiableHeaders.putAll(additionalHeaders);
    }

    @Override
    public String getParameter(final String name)
    {
        String[] strings = getParameterMap().get(name);
        if (strings != null)
        {
            return strings[0];
        }
        return super.getParameter(name);
    }



    public String getHeader(String name) {
        String header = super.getHeader(name);
        return (header != null) ? header : modifiableHeaders.get(name).toString(); // Note: you can't use getParameterValues() here.
    }

    public Enumeration getHeaderNames() {
        List list = new ArrayList();
        for( Enumeration e = super.getHeaderNames() ;  e.hasMoreElements() ; )
            list.add(e.nextElement().toString());
        for( Iterator i = modifiableHeaders.keySet().iterator() ; i.hasNext() ; ){
            list.add(i.next());
    }
        return Collections.enumeration(list);
    }

    @Override
    public Map<String, String[]> getParameterMap()
    {
        if (allParameters == null)
        {
            allParameters = new TreeMap<String, String[]>();
            allParameters.putAll(super.getParameterMap());
            allParameters.putAll(modifiableParameters);
        }
        //Return an unmodifiable collection because we need to uphold the interface contract.
        return Collections.unmodifiableMap(allParameters);
    }

    @Override
    public Enumeration<String> getParameterNames()
    {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public String[] getParameterValues(final String name)
    {
        return getParameterMap().get(name);
    }

    @Override
    public String getQueryString(){

        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, String[]> e : getParameterMap().entrySet()){
            if(sb.length() > 0){
                sb.append('&');
            }
            try {
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue()[0], "UTF-8"));
            } catch (UnsupportedEncodingException e1) {
                e1.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        return sb.toString();
    }

     public void addHeader(String key,  String values){
      modifiableHeaders.put(key,values);
     }

}
