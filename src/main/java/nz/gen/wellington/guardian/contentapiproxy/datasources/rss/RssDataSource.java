package nz.gen.wellington.guardian.contentapiproxy.datasources.rss;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import nz.gen.wellington.guardian.contentapiproxy.datasources.FreeTierContentApi;
import nz.gen.wellington.guardian.contentapiproxy.datasources.GuardianDataSource;
import nz.gen.wellington.guardian.contentapiproxy.datasources.HtmlCleaner;
import nz.gen.wellington.guardian.contentapiproxy.model.Article;
import nz.gen.wellington.guardian.contentapiproxy.model.SearchQuery;
import nz.gen.wellington.guardian.contentapiproxy.model.Section;
import nz.gen.wellington.guardian.contentapiproxy.model.Tag;
import nz.gen.wellington.guardian.contentapiproxy.utils.CachingHttpFetcher;

import org.apache.log4j.Logger;

import com.google.inject.Inject;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedInput;

public class RssDataSource implements GuardianDataSource {

	private static Logger log = Logger.getLogger(RssDataSource.class);

	private static final String API_HOST = "http://www.guardian.co.uk";
	
	// TODO push section filtering to it's own class
	private List<String> badSectionIds = Arrays.asList("community", "crosswords", "extra", "help", "info", "local", "news", "weather");
	
	private CachingHttpFetcher httpFetcher;
	private RssEntryToArticleConvertor rssEntryConvertor;
	private FreeTierContentApi freeTierContentApi;
	private String description;
	private DescriptionFilter descriptionFilter;
		
	@Inject
	public RssDataSource(CachingHttpFetcher httpFetcher, RssEntryToArticleConvertor rssEntryConvertor, FreeTierContentApi freeTierContentApi, DescriptionFilter descriptionFilter) {
		this.httpFetcher = httpFetcher;
		this.rssEntryConvertor = rssEntryConvertor;
		this.freeTierContentApi = freeTierContentApi;
		this.descriptionFilter = descriptionFilter;
	}
	
	
	public List<Article> getArticles(SearchQuery query) {
		String callUrl = buildQueryUrl(query);
		
		log.info("Fetching articles from: " + callUrl);
		final String content = httpFetcher.fetchContent(callUrl, "UTF-8");		
		if (content != null) {
			return extractArticlesFromRss(content);			
		} else {
			log.warn("Failed to fetch content from: " + callUrl);		
		}
		
		return null;
	}


	private List<Article> extractArticlesFromRss(final String content) {
		SyndFeedInput input = new SyndFeedInput();		
		try {
			SyndFeed feed = input.build(new StringReader(content));
				
			description = feed.getDescription();
			
			List<Article> articles = new ArrayList<Article>();
			
			@SuppressWarnings("unchecked")
			List<SyndEntry> entries = feed.getEntries();
			log.info("Found " + entries.size() + " content items");
			
			Map<String, Section> sections = getSections();
			if (sections == null) {
				log.warn("Sections are not available - articles cannot be processed");
				return null;
			}
			
			for (int i = 0; i < entries.size(); i++) {
				SyndEntry item = entries.get(i);
				Article article = rssEntryConvertor.entryToArticle(item, sections);
				if (article != null && article.getSection() != null) {
					articles.add(article);
				}
			}
			return articles;
			
		} catch (IllegalArgumentException e) {
			log.error(e.getMessage());
		} catch (FeedException e) {
			log.error(e.getMessage());
		}
		return null;
	}
	
	
	public Map<String, Section> getSections() {		
		Map<String, Section> sections = freeTierContentApi.getSections();
		if (sections != null) {
			sections = stripHtmlFromSectionNames(sections);
			sections = removeBadSections(sections);			
		}
 		return sections;		
	}
	
	
	public Map<String, List<Tag>> getSectionRefinements(String sectionId) {
		return freeTierContentApi.getSectionRefinements(sectionId);
	}
	
	public Map<String, List<Tag>> getTagRefinements(String tagId) {
		return freeTierContentApi.getTagRefinements(tagId);
	}

	public String getDescription() {
		return descriptionFilter.filterOutMeaninglessDescriptions(description);
	}
	
	
	private Map<String, Section> stripHtmlFromSectionNames(Map<String, Section> sections) {
		Map<String, Section> cleanedSections = new TreeMap<String, Section>();						
		for (String sectionId : sections.keySet()) {
			Section section = sections.get(sectionId);
			section.setName(HtmlCleaner.stripHtml(section.getName()));
			cleanedSections.put(section.getId(), section);
		}		
		return cleanedSections;
	}
	
	
	private Map<String, Section> removeBadSections(Map<String, Section> sections) {
		Map<String, Section> allowedSections = new TreeMap<String, Section>();						
		for (String sectionIds : sections.keySet()) {
			if (!badSectionIds.contains(sectionIds)) {
				Section section = sections.get(sectionIds);
				allowedSections.put(section.getId(), section);				
			}
		}
		return allowedSections;
	}

	
	private String buildQueryUrl(SearchQuery query) {
		StringBuilder queryUrl = new StringBuilder(API_HOST);
		if (query.getSection() != null) {
			queryUrl.append("/" + query.getSection());
		}
		if (query.getTag() != null) {
			queryUrl.append("/" + query.getTag());
		}
		queryUrl.append("/rss");
		return queryUrl.toString();
	}
	
}
