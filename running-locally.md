**These instructions are totally unofficial. They're more for my future reference on how to get this app running quickly**

# Requirements
 - Docker
 - docker-compose

# Steps
We're using docker-compose to spin everything up so you just need docker installed and you're good to go.

    git clone <this repo>
    cd image-service
    docker-compose up # spins up everything
    # open http://localhost:8080/ala-images to view app
    # Ctrl+c to kill the stack
    docker-compose rm # to remove all containers

# How reliable is this?
Not very. It's a quick way to demo the app but that's about it. There's a crude wait loop to make sure that postgres is
ready before the database is created and the app starts but that may not always work. Use with caution.
