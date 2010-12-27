package nz.gen.wellington.guardian.contentapiproxy.servlets;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import nz.gen.wellington.guardian.contentapiproxy.datasources.GuardianDataSource;
import nz.gen.wellington.guardian.contentapiproxy.datasources.rss.RssDataSource;
import nz.gen.wellington.guardian.contentapiproxy.model.Article;
import nz.gen.wellington.guardian.contentapiproxy.model.SearchQuery;
import nz.gen.wellington.guardian.contentapiproxy.model.Tag;

import org.apache.commons.codec.digest.DigestUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@SuppressWarnings("serial")
@Singleton
public class SearchProxyServlet extends CacheAwareProxyServlet {
		
	private GuardianDataSource datasource;
	private ArticleToXmlRenderer articleToXmlRenderer;
	
	@Inject
	public SearchProxyServlet(RssDataSource datasource, ArticleToXmlRenderer articleToXmlRenderer) {
		super();
		this.datasource = datasource;
		this.articleToXmlRenderer = articleToXmlRenderer;
	}
	
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		log.info("Handling request for path: " + request.getRequestURI());
				
		if (request.getRequestURI().equals("/search")) {					
            SearchQuery query = getSearchQueryFromRequest(request);

            // TODO 'true' is not compatible with the Content API spec. Migrate away from this.
            query.setShowAllFields(false); 
            final String showFieldsParameter = request.getParameter("show-fields");
			if (showFieldsParameter != null && (showFieldsParameter.equals("all") || showFieldsParameter.equals("true"))) {
                query.setShowAllFields(true);
            }
            
            // TODO 'true' is not compatible with the Content API spec. Migrate away from this.
			query.setShowAllTags(false);
            String showAllTagsParameter = request.getParameter("show-tags");
			if (showAllTagsParameter != null && (showAllTagsParameter.equals("all") || showAllTagsParameter.equals("true"))) {
                query.setShowAllTags(true);
            }
			
            final String queryCacheKey = getQueryCacheKey(request);
            String output = cacheGet(queryCacheKey);
            if (output != null) {
            	log.info("Returning cached results for call url: " + queryCacheKey);				
            } 
            
			if (output == null) {			
				log.info("Building result for call: " + queryCacheKey);	
				output = getContent(query);
				if (output != null) {
					cacheContent(queryCacheKey, output);
				}
				
			}
			
			if (output != null) {
				log.info("Outputing content: " + output.length() + " characters");
				response.setStatus(HttpServletResponse.SC_OK);
				response.setContentType("text/xml");
				response.setCharacterEncoding("UTF-8");
				response.addHeader("Etag", DigestUtils.md5Hex(output));
				PrintWriter writer = response.getWriter();
				writer.print(output);
				writer.flush();
				
			} else {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);				
			}
		}
		
		return;
	}

	
	private String getContent(SearchQuery query) {
		List<Article> articles = datasource.getArticles(query);
		if (articles == null) {
			return null;
		}
		
		Map<String, List<Tag>> refinements = null;

		final boolean isSectionQuery = query.getSections() != null && query.getSections().size() == 1;
		if (isSectionQuery) {
			refinements = datasource.getSectionRefinements(query.getSections().get(0));
		} else if (query.getTags() != null && query.getTags().size() == 1) {
			refinements = datasource.getTagRefinements(query.getTags().get(0));
		}
		
		return articleToXmlRenderer.outputXml(articles, datasource.getDescription(), refinements, query.isShowAllFields());
	}
	
	
	private SearchQuery getSearchQueryFromRequest(HttpServletRequest request) {
		SearchQuery query = new SearchQuery();
		if (request.getParameter("section") != null) {
			extractIds(request.getParameter("section"), query);
		}
		if (request.getParameter("tag") != null) {
			extractIds(request.getParameter("tag"), query);
		}
		
		if (request.getParameter("page-size") != null) {
			try {
				Integer pageSize = Integer.parseInt(request.getParameter("page-size"));
				log.debug("Query page size set to: " + pageSize);
				query.setPageSize(pageSize);
			} catch (NumberFormatException e) {
			}
		}
		return query;
	}


	private void extractIds(String parameter, SearchQuery query) {
		parameter = URLDecoder.decode(parameter);
		log.info("Parameter: " + parameter);
		String[] fields = parameter.split("\\|");
		List<String> asList = Arrays.asList(fields);
		log.info(asList);
		for (String field : asList) {
			log.info("Field: " + field);

			String[] sectionAndTagIds = field.split("/");
			String sectionId = sectionAndTagIds[0];
			
			if (sectionAndTagIds.length > 1) {
				String tagId = sectionAndTagIds[1];
				if (sectionId.equals(tagId)) {
					query.addSection(sectionId);
				} else {
					query.addTag(field);
				}
				
			} else {
				query.addSection(sectionId);
			}			
		}
	}

}