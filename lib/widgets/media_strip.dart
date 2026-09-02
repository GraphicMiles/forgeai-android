import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../theme.dart';

/// What Luna saw while it was browsing.
///
/// A search for "the latest video" that answers in prose only is technically
/// correct and practically useless — the person asked to see something. The
/// engine collects the addresses of pictures and video posters off the page it
/// loaded; this turns them into something to look at.
///
/// Nothing here downloads on its own beyond the thumbnails themselves, and a
/// video only starts when it is tapped.
class MediaStrip extends StatelessWidget {
  const MediaStrip(this.items, {super.key});

  final List<Map<String, dynamic>> items;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const SizedBox.shrink();

    return Padding(
      padding: const EdgeInsets.only(top: 10, bottom: 2),
      child: SizedBox(
        height: 132,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: EdgeInsets.zero,
          itemCount: items.length,
          separatorBuilder: (_, __) => const SizedBox(width: 8),
          itemBuilder: (BuildContext context, int index) =>
              _Tile(items[index]),
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile(this.item);

  final Map<String, dynamic> item;

  String get _src => (item['src'] as String?) ?? '';
  String get _kind => (item['kind'] as String?) ?? 'image';
  String get _title => (item['title'] as String?) ?? '';
  String get _id => (item['id'] as String?) ?? '';
  String get _page => (item['page'] as String?) ?? '';

  bool get _isVideo => _kind == 'video';

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => _open(context),
      child: ClipRRect(
        borderRadius: LunaTheme.rNote,
        child: Container(
          width: _isVideo ? 200 : 132,
          color: LunaTheme.fill,
          child: Stack(
            fit: StackFit.expand,
            children: <Widget>[
              Image.network(
                _src,
                fit: BoxFit.cover,
                // A thumbnail that will not load is not worth an error box;
                // it becomes a plain tile that still opens.
                errorBuilder: (_, __, ___) => Center(
                  child: FaIcon(
                    _isVideo ? FontAwesomeIcons.play : FontAwesomeIcons.image,
                    size: 18,
                    color: LunaTheme.ink4,
                  ),
                ),
                loadingBuilder: (BuildContext _, Widget child,
                    ImageChunkEvent? progress) {
                  if (progress == null) return child;
                  return Center(
                    child: SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 1.5,
                        color: LunaTheme.ink4,
                      ),
                    ),
                  );
                },
              ),
              if (_isVideo)
                Center(
                  child: Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.55),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(Icons.play_arrow_rounded,
                        color: Colors.white, size: 24),
                  ),
                ),
              if (_title.isNotEmpty)
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  child: Container(
                    padding: const EdgeInsets.fromLTRB(8, 10, 8, 6),
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: <Color>[
                          Colors.black.withValues(alpha: 0),
                          Colors.black.withValues(alpha: 0.75),
                        ],
                      ),
                    ),
                    child: Text(
                      _title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        height: 1.25,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  void _open(BuildContext context) {
    if (_isVideo && _id.isNotEmpty) {
      Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => _VideoPage(id: _id, title: _title),
      ));
      return;
    }
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => _ImagePage(src: _src, title: _title, page: _page),
    ));
  }
}

/// A video, played inside Luna.
///
/// The embed player rather than the watch page: it is the part that plays,
/// without the rest of YouTube's chrome coming along with it.
class _VideoPage extends StatefulWidget {
  const _VideoPage({required this.id, required this.title});

  final String id;
  final String title;

  @override
  State<_VideoPage> createState() => _VideoPageState();
}

class _VideoPageState extends State<_VideoPage> {
  late final WebViewController _controller;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      ..loadRequest(Uri.parse(
          'https://www.youtube.com/embed/${widget.id}?autoplay=1&playsinline=1'));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        elevation: 0,
        title: Text(
          widget.title.isEmpty ? 'Video' : widget.title,
          style: const TextStyle(fontSize: 14, color: Colors.white),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
      body: Center(
        child: AspectRatio(
          aspectRatio: 16 / 9,
          child: WebViewWidget(controller: _controller),
        ),
      ),
    );
  }
}

/// One picture, full screen, pinchable.
class _ImagePage extends StatelessWidget {
  const _ImagePage(
      {required this.src, required this.title, required this.page});

  final String src;
  final String title;
  final String page;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        elevation: 0,
        title: Text(
          title.isEmpty ? 'Image' : title,
          style: const TextStyle(fontSize: 14, color: Colors.white),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
      body: Center(
        child: InteractiveViewer(
          minScale: 1,
          maxScale: 5,
          child: Image.network(
            src,
            fit: BoxFit.contain,
            errorBuilder: (_, __, ___) => const Text(
              'That picture would not load.',
              style: TextStyle(color: Colors.white70, fontSize: 13),
            ),
          ),
        ),
      ),
    );
  }
}
