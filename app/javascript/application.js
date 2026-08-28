// Configure your import map in config/importmap.rb. Read more: https://github.com/rails/importmap-rails
import "@hotwired/turbo-rails"
import "controllers"
import "broadcasts"
document.addEventListener('ajax:beforeSend', function(event) {
    var token = document.querySelector('meta[name="csrf-token"]').getAttribute('content');
    event.detail[0].setRequestHeader('X-CSRF-Token', token);
  });
  
