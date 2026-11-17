/* Atoms Demo slide deck engine. Renders the JSON spec found in #deck-spec.
   The same file powers seeded templates and freshly generated decks, so preview,
   gallery thumbnails and the finished deck always agree. */
(function () {
  'use strict'

  var specNode = document.getElementById('deck-spec')
  if (!specNode) return
  var spec = JSON.parse(specNode.textContent)
  var slides = Array.isArray(spec.slides) ? spec.slides : []
  if (!slides.length) return

  var params = new URLSearchParams(location.search)
  var peek = params.get('peek') === '1'
  var start = Math.max(0, Math.min(slides.length - 1, parseInt(params.get('slide') || '1', 10) - 1 || 0))
  var current = start
  var brand = spec.brand || {}
  var timer = null

  if (peek) document.body.classList.add('is-peek')
  document.body.setAttribute('data-theme', spec.theme || 'swiss')
  document.title = spec.title || '幻灯片'

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
  }

  // Long headlines otherwise overflow the 1280px stage; the CSS ladder keys off this bucket.
  function sizeBucket(value) {
    var length = String(value == null ? '' : value).length
    if (length > 22) return 'long'
    if (length > 14) return 'md'
    return 'sm'
  }

  function pageTag(index) {
    return '<span class="page-tag">' + String(index + 1).padStart(2, '0') + ' / ' + String(slides.length).padStart(2, '0') + '</span>'
  }

  function brandFoot() {
    return brand.name ? '<span class="brand-foot">' + esc(brand.name) + '</span>' : ''
  }

  function renderSlide(slide, index) {
    var body = ''
    var kind = slide.layout || 'bullets'
    var foot = brandFoot()

    if (kind === 'cover') {
      body = '<div class="slide-inner">'
        + (slide.kicker ? '<p class="kicker">' + esc(slide.kicker) + '</p>' : '')
        + '<h1 data-len="' + sizeBucket(slide.title) + '">' + esc(slide.title) + '</h1>'
        + (slide.subtitle ? '<p class="cover-sub">' + esc(slide.subtitle) + '</p>' : '')
        + (Array.isArray(slide.meta) && slide.meta.length
          ? '<div class="cover-meta">' + slide.meta.map(function (item) { return '<span>' + esc(item) + '</span>' }).join('') + '</div>'
          : '')
        + '</div>'
    } else if (kind === 'section') {
      body = '<div class="slide-inner">'
        + '<span class="section-index">' + esc(slide.index || String(index).padStart(2, '0')) + '</span>'
        + '<h1>' + esc(slide.title) + '</h1>'
        + (slide.subtitle ? '<p>' + esc(slide.subtitle) + '</p>' : '')
        + '</div>'
    } else if (kind === 'agenda') {
      var items = (slide.items || []).map(function (item, position) {
        return '<div class="agenda-item"><span class="agenda-no">' + String(position + 1).padStart(2, '0') + '</span>'
          + '<span class="agenda-title">' + esc(item.title || item) + '</span></div>'
      }).join('')
      body = '<div class="slide-inner">'
        + (slide.kicker ? '<p class="kicker">' + esc(slide.kicker) + '</p>' : '<p class="kicker">目录</p>')
        + '<h2>' + esc(slide.title || '本次演示') + '</h2><div class="rule"></div>'
        + '<div class="agenda-list">' + items + '</div>'
        + '</div>'
    } else if (kind === 'statement') {
      body = '<div class="slide-inner">'
        + '<blockquote>' + esc(slide.text || slide.title) + '</blockquote>'
        + (slide.subtitle ? '<p style="margin-top:26px">' + esc(slide.subtitle) + '</p>' : '')
        + '</div>'
    } else if (kind === 'stats') {
      var stats = (slide.stats || []).map(function (stat) {
        return '<div class="stat"><div class="stat-value">' + esc(stat.value) + '</div>'
          + '<div class="stat-label">' + esc(stat.label) + '</div>'
          + (stat.note ? '<div class="stat-note">' + esc(stat.note) + '</div>' : '') + '</div>'
      }).join('')
      body = '<div class="slide-inner">'
        + (slide.kicker ? '<p class="kicker">' + esc(slide.kicker) + '</p>' : '')
        + '<h2>' + esc(slide.title) + '</h2><div class="rule"></div>'
        + '<div class="stat-grid">' + stats + '</div>'
        + '</div>'
    } else if (kind === 'quote') {
      body = '<div class="slide-inner">'
        + '<div class="quote-mark">“</div>'
        + '<blockquote>' + esc(slide.quote || slide.title) + '</blockquote>'
        + (slide.attribution ? '<p class="attribution">' + esc(slide.attribution) + '</p>' : '')
        + '</div>'
    } else if (kind === 'closing') {
      body = '<div class="slide-inner">'
        + '<h1 data-len="' + sizeBucket(slide.title) + '">' + esc(slide.title || '谢谢观看') + '</h1>'
        + (slide.subtitle ? '<p>' + esc(slide.subtitle) + '</p>' : '')
        + '</div>'
    } else {
      var bullets = (slide.bullets || []).map(function (item) {
        return '<div class="bullet">' + esc(item) + '</div>'
      }).join('')
      body = '<div class="slide-inner">'
        + (slide.kicker ? '<p class="kicker">' + esc(slide.kicker) + '</p>' : '')
        + '<h2>' + esc(slide.title) + '</h2><div class="rule"></div>'
        + '<div class="bullet-list">' + bullets + '</div>'
        + (slide.note ? '<p class="note">' + esc(slide.note) + '</p>' : '')
        + '</div>'
    }

    return '<section class="slide layout-' + esc(kind) + (index === current ? ' is-current' : '') + '" data-index="' + index + '">'
      + body + foot + pageTag(index) + '</section>'
  }

  var stage = document.getElementById('stage')
  var rail = document.getElementById('rail-list')
  var counter = document.getElementById('deck-count')

  stage.innerHTML = slides.map(renderSlide).join('')
  var nodes = Array.prototype.slice.call(stage.querySelectorAll('.slide'))

  if (rail) {
    rail.innerHTML = slides.map(function (slide, index) {
      return '<button class="thumb" type="button" data-index="' + index + '" aria-label="第 ' + (index + 1) + ' 页">'
        + '<span class="thumb-clip"><span class="thumb-scale">' + renderSlide(slide, index) + '</span></span>'
        + '<span class="thumb-no">' + String(index + 1).padStart(2, '0') + ' · ' + esc(slide.title || slide.text || '') + '</span>'
        + '</button>'
    }).join('')
    rail.addEventListener('click', function (event) {
      var button = event.target.closest('.thumb')
      if (button) go(parseInt(button.getAttribute('data-index'), 10))
    })
  }

  function go(index) {
    if (index < 0 || index >= nodes.length || index === current) return
    nodes[current].classList.remove('is-current')
    current = index
    nodes[current].classList.add('is-current')
    sync()
  }

  function sync() {
    if (counter) counter.textContent = (current + 1) + ' / ' + nodes.length
    if (rail) {
      Array.prototype.forEach.call(rail.children, function (child, index) {
        child.setAttribute('aria-current', index === current ? 'true' : 'false')
      })
    }
    var prev = document.getElementById('prev')
    var next = document.getElementById('next')
    if (prev) prev.disabled = current === 0
    if (next) next.disabled = current === nodes.length - 1
    var active = rail && rail.children[current]
    if (active && !peek) active.scrollIntoView({ block: 'nearest' })
    window.__atomsDeck = { index: current, total: nodes.length }
  }

  function scale() {
    var holder = document.getElementById('stage-holder')
    if (!holder) return
    var box = holder.getBoundingClientRect()
    var ratio = Math.min(box.width / 1280, box.height / 720)
    stage.style.transform = 'scale(' + Math.max(0.05, ratio) + ')'
  }

  document.getElementById('prev') && document.getElementById('prev').addEventListener('click', function () { go(current - 1) })
  document.getElementById('next') && document.getElementById('next').addEventListener('click', function () { go(current + 1) })

  document.addEventListener('keydown', function (event) {
    if (peek) return
    if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') { event.preventDefault(); go(current + 1) }
    if (event.key === 'ArrowLeft' || event.key === 'PageUp') { event.preventDefault(); go(current - 1) }
    if (event.key === 'Home') { event.preventDefault(); go(0) }
    if (event.key === 'End') { event.preventDefault(); go(nodes.length - 1) }
  })

  window.addEventListener('resize', scale)
  if (window.ResizeObserver) new ResizeObserver(scale).observe(document.getElementById('stage-holder'))

  // Peek mode runs itself: the gallery card is a silent carousel of the real deck.
  if (peek && nodes.length > 1) {
    var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (!reduce) {
      timer = window.setInterval(function () {
        go((current + 1) % nodes.length)
      }, 1900)
      document.addEventListener('visibilitychange', function () {
        if (document.hidden) { window.clearInterval(timer); timer = null }
      })
    }
  }

  sync()
  scale()
  requestAnimationFrame(scale)
})()
