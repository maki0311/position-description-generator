# Federal Position Description Generator

An AI-powered web application built with Java and Spring Boot that helps users create, analyze, and update federal government position descriptions (PDs). The application integrates OpenAI and the USAJOBS API to assist with position classification, duty statement refinement, and the development of structured position description documents.

## Features

- **Guided PD Creation:** A five-step wizard guides users through the position description development process.
- **Existing PD Revision:** Update and refine existing position descriptions based on revised duties, responsibilities, and position requirements.
- **AI-Powered Classification:** Generate recommendations for occupational series and grade based on position duties and requirements.
- **Duty Statement Rewriting:** Use AI to improve the clarity and quality of position duty statements.
- **Federal Classification Support:** Support Factor Evaluation System (FES) and General Schedule Supervisory Guide (GSSG) evaluation workflows.
- **USAJOBS Integration:** Retrieve federal job information through the USAJOBS API.
- **PDF Generation:** Generate formatted position description documents.
- **REST API:** Backend endpoints support PD generation, series recommendations, duty rewriting, and USAJOBS data retrieval.

## Tech Stack

| Technology | Purpose |
|---|---|
| Java 17 | Backend development |
| Spring Boot 3.x | Application framework and REST APIs |
| JavaScript | Frontend functionality |
| HTML5 and CSS3 | User interface and styling |
| OpenAI API | AI-powered content generation and revision |
| USAJOBS API | Federal job information retrieval |
| Apache PDFBox | PDF processing and generation |
| Maven | Build and dependency management |

## Requirements

- Java 17 or later
- Maven 3.6 or later
- OpenAI API key
- USAJOBS API credentials
- Internet connection for external API integrations

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/maki0311/positiondescriptiongenerator.git
cd positiondescriptiongenerator
```

### 2. Configure API Credentials

Configure the required API credentials using the property names expected by the application.

In `src/main/resources/application.properties`, the relevant settings include:

```properties
server.port=8080
OPENAI_API_KEY=your_openai_api_key
USAJOBS_USER_AGENT=your_email_address
USAJOBS_API_KEY=your_usajobs_api_key
```

Replace the placeholder values with your own credentials.

**Security:** Never commit real API keys or other secrets to GitHub. Keep personal credentials in a local configuration file excluded from version control, or use environment variables if supported by the application.

- [OpenAI API](https://openai.com/api/)
- [USAJOBS API Registration](https://developer.usajobs.gov/APIRequest/Index)

### 3. Build and Run the Application

Build the project:

```bash
mvn clean install
```

Start the Spring Boot application:

```bash
mvn spring-boot:run
```

### 4. Open the Application

Navigate to:

[http://localhost:8080](http://localhost:8080)

## Main API Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/api/generate-pd` | POST | Generate a complete position description |
| `/api/recommend-series` | POST | Recommend an occupational series based on position duties |
| `/api/rewrite-duties` | POST | Rewrite and refine duty statements using AI |
| `/api/proxy/usajobs` | GET | Retrieve USAJOBS data through the application backend |

## Project Structure

```text
src/main/
├── java/com/example/pdgenerator/
│   ├── PdGeneratorApplication.java
│   ├── controller/
│   │   └── PdGeneratorController.java
│   └── service/
│       ├── PdService.java
│       └── PdfProcessingService.java
├── resources/
│   ├── application.properties
│   └── pdfs/
└── public/
    ├── index.html
    ├── PD_Script.js
    └── styles.css

pom.xml
```

## Troubleshooting

**Application fails to start**
- Verify that Java 17 or later is installed.
- Confirm that Maven is installed and dependencies resolve successfully.
- Check that port 8080 is available.
- Verify that the application configuration is correct.

**AI requests fail**
- Confirm that the OpenAI API key is valid.
- Check API account access, usage limits, and billing.
- Review application logs for errors.

**USAJOBS data is unavailable**
- Verify the USAJOBS API key and registered user-agent email.
- Check the USAJOBS API service status and applicable request limits.
- Review the application logs for failed requests.

## License and Usage

Provided as-is. No formal open-source license is specified unless a separate license file is included in the repository.

## Version

**Version:** 1.0.0  
**Last documented update:** October 2025

