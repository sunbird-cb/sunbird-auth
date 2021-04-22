<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "title">
    ${message.summary}
    <#elseif section = "form">
    <div class="custom-wrapper">
        <div class="custom-grid">
            <div class="ui raised shadow container segment fullpage-background-image left-box">
                <div class="ui column height-fix" style="padding:0">
                    <div class="logo" style="width: 320px; max-width:100%">
                        <img src="${url.resourcesPath}/img/dopt_logo.png" alt="Logo" style="width: inherit;">
                    </div>
                    <div class="img-container" style="width: 320px; max-width:100%">
                        <div class="slideshow-container">

                            <div class="mySlides fade">
                                <img src="${url.resourcesPath}/img/Hexagon.png" alt="hexagon image">
                            </div>

                            <div class="mySlides fade">
                                <img src="${url.resourcesPath}/img/card1.png" alt="card image 1">
                            </div>

                            <div class="mySlides fade">
                                <img src="${url.resourcesPath}/img/card2.png" alt="card image 2">
                            </div>

                        </div>
                    </div>
                    <div class="dots-menu">
                        <label class="dot">*</label>
                        <label class="dot">*</label>
                        <label class="dot">*</label>
                    </div>
                </div>
                
            </div>
            <div class="ui raised shadow container segment fullpage-background-image login-section">
                <div class="ui one column grid stackable">
                    <#--  <div class="ui column tablet only computer only"></div>  -->
                    <div class="ui column height-fix">
                        <div class="max-container mw-100">
                            <div id="kc-info-message">
                                <p class="instruction signUpMsg">${message.summary}</p>
                                <#if skipLink??>
                                <#else>
                                    <#if pageRedirectUri??>
                                        <p class="signUpMsg"><a class="signUpLink" href="${pageRedirectUri}">${msg("backToApplication")}</a></p>
                                    <#elseif client.baseUrl??>
                                        <p class="signUpMsg"><a class="signUpLink" href="${client.baseUrl}">${msg("backToApplication")}</a></p>
                                    </#if>
                                </#if>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
    <script type="text/javascript">
        var slideIndex = 0;
        showSlides();

        function showSlides() {
        var i;
        var slides = document.getElementsByClassName("mySlides");
        var dots = document.getElementsByClassName("dot");
        for (i = 0; i < slides.length; i++) {
            slides[i].style.display = "none";  
        }
        slideIndex++;
        if (slideIndex > slides.length) {slideIndex = 1}    
        for (i = 0; i < dots.length; i++) {
            dots[i].className = dots[i].className.replace(" active", "");
        }
        slides[slideIndex-1].style.display = "block";  
        dots[slideIndex-1].className += " active";
        setTimeout(showSlides, 5000); // Change image every 5 seconds
        }

    </script>
    </#if>
</@layout.registrationLayout>