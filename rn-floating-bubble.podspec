require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "rn-floating-bubble"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = { :type => package["license"], :file => "LICENSE" }
  s.authors      = package["author"]
  s.platforms    = { :ios => "13.0" }

  s.source = {
    :git => package["repository"]["url"],
    :tag => "#{s.version}"
  }

  # Android-only library — the .m stub lets CocoaPods compile the pod without
  # errors on iOS.  No functionality is provided on iOS.
  s.source_files = "ios/**/*.{h,m,mm}"

  s.dependency "React-Core"
end
