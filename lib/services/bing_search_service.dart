import 'package:dio/dio.dart';
import 'package:logger/logger.dart';

class SearchResult {
  final String title;
  final String snippet;
  final String url;

  SearchResult({
    required this.title,
    required this.snippet,
    required this.url,
  });
}

class BingSearchService {
  final Logger _logger = Logger();
  final Dio _dio = Dio();

  bool get isConfigured => true;

  void configure({String? apiKey, String? endpoint}) {
    _logger.i('Search service configured (Bing crawler mode)');
  }

  Future<String?> search(String query, {int count = 5}) async {
    try {
      final results = await _searchBing(query, count: count);
      if (results == null || results.isEmpty) {
        return null;
      }

      final searchResults = results.map((r) {
        return '${r.title}\n${r.snippet}\n来源: ${r.url}';
      }).join('\n\n');

      _logger.i('Bing search returned ${results.length} results');
      return searchResults;
    } catch (e) {
      _logger.e('Bing search error: $e');
      return null;
    }
  }

  Future<List<SearchResult>?> _searchBing(String query, {int count = 5}) async {
    try {
      final response = await _dio.get(
        'https://www.bing.com/search',
        options: Options(
          headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Accept-Encoding': 'gzip, deflate, br',
            'Connection': 'keep-alive',
          },
        ),
        queryParameters: {
          'q': query,
          'setlang': 'zh-CN',
          'first': 1,
          'count': count,
        },
      );

      final html = response.data as String;
      return _parseBingHtml(html, count);
    } catch (e) {
      _logger.e('Bing search request error: $e');
      return null;
    }
  }

  List<SearchResult>? _parseBingHtml(String html, int count) {
    final results = <SearchResult>[];

    final liPattern = RegExp(
      r'<li[^>]*class="[^"]*b_algo[^"]*"[^>]*>.*?<h2[^>]*>.*?<a[^>]*href="([^"]*)"[^>]*>([^<]*(?:<[^>]*>[^<]*</[^>]*>)*[^<]*)</a>.*?</h2>.*?(?:<p[^>]*>([^<]*(?:<[^>]*>[^<]*</[^>]*>)*[^<]*)</p>)?',
      dotAll: true,
    );

    final matches = liPattern.allMatches(html);
    for (final match in matches.take(count)) {
      final url = match.group(1) ?? '';
      var title = _stripHtmlTags(match.group(2) ?? '');
      var snippet = _stripHtmlTags(match.group(3) ?? '');

      if (title.isNotEmpty && url.isNotEmpty) {
        results.add(SearchResult(
          title: title.trim(),
          snippet: snippet.trim(),
          url: url,
        ));
      }
    }

    if (results.isEmpty) {
      final simplePattern = RegExp(
        r'<a[^>]*href="(https?://[^"]*)"[^>]*class="[^"]*b_title[^"]*"[^>]*>([^<]*)</a>',
        dotAll: true,
      );
      final simpleMatches = simplePattern.allMatches(html);
      for (final match in simpleMatches.take(count)) {
        final url = match.group(1) ?? '';
        final title = _stripHtmlTags(match.group(2) ?? '');
        if (title.isNotEmpty && url.isNotEmpty) {
          results.add(SearchResult(
            title: title.trim(),
            snippet: '',
            url: url,
          ));
        }
      }
    }

    return results.isNotEmpty ? results : null;
  }

  String _stripHtmlTags(String html) {
    return html
        .replaceAll(RegExp(r'<[^>]*>'), '')
        .replaceAll(RegExp(r'\s+'), ' ')
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll('&#39;', "'")
        .trim();
  }

  Future<Map<String, String>?> searchWithUrls(String query, {int count = 5}) async {
    final results = await search(query, count: count);
    if (results == null) return null;

    final Map<String, String> searchResults = {};
    final lines = results.split('\n\n');
    for (final block in lines) {
      final blockLines = block.split('\n');
      if (blockLines.length >= 2) {
        final url = blockLines.last.replaceFirst('来源: ', '').trim();
        final content = blockLines.take(blockLines.length - 1).join('\n');
        if (url.isNotEmpty) {
          searchResults[url] = content;
        }
      }
    }
    return searchResults;
  }
}